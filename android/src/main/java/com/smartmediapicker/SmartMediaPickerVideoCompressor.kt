package com.smartmediapicker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

object SmartMediaPickerVideoCompressor {

    fun compressVideo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        quality: String,
        onSuccess: (File, Int, Int, Long) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Thread {
            try {
                val extractor = MediaExtractor()
                context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: throw Exception("Failed to open source file descriptor")

                var videoTrackIndex = -1
                var audioTrackIndex = -1
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && videoTrackIndex == -1) {
                        videoTrackIndex = i
                        videoFormat = format
                    } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                }

                if (videoTrackIndex == -1) {
                    throw Exception("No video track found")
                }

                // Prepare Muxer
                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var newVideoTrackIndex = -1
                var newAudioTrackIndex = -1

                // Select target dimensions and bitrate based on quality
                val originalWidth = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
                val originalHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
                val originalBitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                } else {
                    10000000 // default 10 Mbps
                }

                val scaleFactor = when (quality.lowercase()) {
                    "low" -> 0.4f
                    "medium" -> 0.6f
                    else -> 0.8f
                }

                val targetWidth = ((originalWidth * scaleFactor).toInt() / 16) * 16
                val targetHeight = ((originalHeight * scaleFactor).toInt() / 16) * 16
                val targetBitrate = (originalBitrate * scaleFactor * scaleFactor).toInt().coerceIn(800000, 5000000)

                // Set up Video Encoder & Decoder
                val videoMime = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
                val encoderFormat = MediaFormat.createVideoFormat(videoMime, targetWidth, targetHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }

                val encoder = MediaCodec.createEncoderByType(videoMime)
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()

                val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(videoFormat, inputSurface, null, 0)
                decoder.start()

                extractor.selectTrack(videoTrackIndex)

                // Transcoding Loop
                val decoderBufferInfo = MediaCodec.BufferInfo()
                val encoderBufferInfo = MediaCodec.BufferInfo()
                var allInputExtracted = false
                var allOutputDecoded = false
                var allEncoded = false

                var lastPresentationTimeUs = 0L

                while (!allEncoded) {
                    // 1. Feed Decoder from Extractor
                    if (!allInputExtracted) {
                        val inBufferIndex = decoder.dequeueInputBuffer(10000)
                        if (inBufferIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inBufferIndex)!!
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                allInputExtracted = true
                            } else {
                                decoder.queueInputBuffer(inBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // 2. Dequeue from Decoder and render to Surface (which feeds Encoder)
                    if (!allOutputDecoded) {
                        val outBufferIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, 10000)
                        if (outBufferIndex >= 0) {
                            val render = decoderBufferInfo.size > 0
                            decoder.releaseOutputBuffer(outBufferIndex, render)
                            if ((decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoder.signalEndOfInputStream()
                                allOutputDecoded = true
                            }
                        }
                    }

                    // 3. Dequeue from Encoder and write to Muxer
                    val encBufferIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 10000)
                    if (encBufferIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(encBufferIndex)!!
                        if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encoderBufferInfo.size = 0
                        }

                        if (encoderBufferInfo.size > 0) {
                            if (newVideoTrackIndex == -1) {
                                newVideoTrackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                            }
                            encodedData.position(encoderBufferInfo.offset)
                            encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                            muxer.writeSampleData(newVideoTrackIndex, encodedData, encoderBufferInfo)
                            lastPresentationTimeUs = encoderBufferInfo.presentationTimeUs
                        }

                        encoder.releaseOutputBuffer(encBufferIndex, false)

                        if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            allEncoded = true
                        }
                    } else if (encBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (newVideoTrackIndex == -1) {
                            newVideoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            // Audio track setup if exists
                            if (audioFormat != null) {
                                newAudioTrackIndex = muxer.addTrack(audioFormat)
                            }
                            muxer.start()
                        }
                    }
                }

                // Clean video codec
                decoder.stop()
                decoder.release()
                encoder.stop()
                encoder.release()

                // Copy Audio Track if exists
                if (audioFormat != null && newAudioTrackIndex != -1) {
                    extractor.unselectTrack(videoTrackIndex)
                    extractor.selectTrack(audioTrackIndex)
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    val audioBuffer = ByteBuffer.allocate(1024 * 256)
                    val audioBufferInfo = MediaCodec.BufferInfo()

                    while (true) {
                        val sampleSize = extractor.readSampleData(audioBuffer, 0)
                        if (sampleSize < 0) break

                        audioBufferInfo.offset = 0
                        audioBufferInfo.size = sampleSize
                        audioBufferInfo.presentationTimeUs = extractor.sampleTime
                        audioBufferInfo.flags = extractor.sampleFlags

                        muxer.writeSampleData(newAudioTrackIndex, audioBuffer, audioBufferInfo)
                        extractor.advance()
                    }
                }

                extractor.release()
                muxer.stop()
                muxer.release()

                val duration = lastPresentationTimeUs / 1000L // to millis
                onSuccess(outputFile, targetWidth, targetHeight, duration)

            } catch (e: Exception) {
                e.printStackTrace()
                onFailure(e)
            }
        }.start()
    }
}
