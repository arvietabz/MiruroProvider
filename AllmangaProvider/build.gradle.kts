dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "Allmanga via Anivexa Aggregator API"
    authors = listOf("arvietabz")
    status = 1 // 1 = Ok
    tvTypes = listOf("Anime", "AnimeMovie")
    requiresResources = true
    language = "en"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}