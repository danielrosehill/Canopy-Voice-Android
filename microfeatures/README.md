# microfeatures

The microWakeWord audio frontend (TensorFlow Lite Micro `microfrontend`, kissfft) behind a small JNI wrapper,
vendored unchanged from [brownard/Ava](https://github.com/brownard/Ava) commit `2c76f51` (Apache-2.0, see
`LICENSE-Ava`; TensorFlow and kissfft keep their own headers). Only the test files and kissfft `tools/` were
dropped. The Kotlin package stays `com.example.microfeatures` because the JNI symbol names depend on it.
