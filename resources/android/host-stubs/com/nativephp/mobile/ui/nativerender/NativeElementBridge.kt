// Stand-in for NativePHP's NativeElementBridge, used only by the hostCheck compile.
package com.nativephp.mobile.ui.nativerender

class NativeElementBridge private constructor() {
    companion object {
        fun sendNativeEvent(eventName: String, payloadJson: String) {}
    }
}
