# Room, Media3, Play Services Cast, OkHttp, and kotlinx.serialization each ship their own
# consumer ProGuard rules inside their artifacts (checked directly in each AAR/JAR's
# META-INF/proguard or proguard.txt as of the versions pinned in libs.versions.toml), so AGP
# merges those in automatically. This file only needs whatever those don't cover.
