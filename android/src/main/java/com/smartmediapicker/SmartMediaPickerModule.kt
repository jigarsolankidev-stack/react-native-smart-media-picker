package com.smartmediapicker

import com.facebook.react.bridge.ReactApplicationContext

class SmartMediaPickerModule(reactContext: ReactApplicationContext) :
  NativeSmartMediaPickerSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeSmartMediaPickerSpec.NAME
  }
}
