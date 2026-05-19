# APK Size Tracker 🔍📊

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/apk-size-tracker.svg)](https://plugins.jenkins.io/apk-size-tracker)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/apk-size-tracker.svg)](https://plugins.jenkins.io/apk-size-tracker)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A Jenkins plugin that automatically tracks **APK** and **IPA** build artifact sizes and shows a beautiful **ECharts trend chart** with size diff comparisons.

![APK Size Trend Chart](https://plugins.jenkins.io/apk-size-tracker/screenshot)

## Features ✨

- **Automatic tracking** — captures .apk and .ipa sizes as a post-build step
- **ECharts trend chart** — interactive line chart with gradient fill, zoom, and save-as-image
- **Diff banner** — shows size change (↑ red / ↓ green) vs the previous build at a glance
- **Build detail table** — last 5 builds with size and duration
- **Persistent data cache** — build records stored on disk; chart loads in milliseconds
- **Backward compatible** — automatically scans historical artifacts for pre-installation data
- **Zero external dependencies** — ECharts library bundled inside the plugin (no CDN)
- **Works offline** — no internet connection required after installation

## Getting Started 🚀

### Prerequisites

- Jenkins **2.479.3** or newer
- Java **17** or newer

### Installation

1. Go to **Manage Jenkins** → **Plugins** → **Available Plugins**
2. Search for "APK Size Tracker"
3. Check the box and click **Install without restart**
4. Done! 🎉

Or download the HPI file from the [releases page](https://github.com/QAwangyang/apk-size-tracker-plugin/releases) and install manually.

### Usage

1. Open your **Freestyle** or **Pipeline** project configuration
2. In the **Post-build Actions** section, click **Add post-build action**
3. Select **"Track APK/IPA Size"**
4. Configure options (optional):
   - **Track Android** — enable/disable .apk scanning (default: on)
   - **Track iOS** — enable/disable .ipa scanning (default: on)
5. **Make sure** you have an **"Archive the artifacts"** step before this plugin (the plugin scans archived artifacts)
6. Save and run a build

After the build completes, you'll see:
- 📊 **"APK Size Trend"** link in the project sidebar → click to view the full chart
- 📋 **"APK Size"** entry on each build page → shows the captured sizes

### Pipeline Example

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                // Your build steps here
            }
        }
        stage('Archive') {
            steps {
                archiveArtifacts artifacts: '**/*.apk, **/*.ipa'
            }
        }
    }
    post {
        success {
            step([$class: 'ApkSizePublisher', trackAndroid: true, trackIos: true])
        }
    }
}
```

> **Note:** The "Archive the artifacts" step must run *before* the APK Size Tracker step.

## Chart Preview 📈

The chart page features:
- **Interactive ECharts** — hover, zoom, pan, and save as image
- **APK (blue line)** and **IPA (orange diamond)** size trends
- **Gradient fill** — easy visual comparison
- **Build diff banner** — immediately see if the latest build grew or shrank
- **Last 5 builds table** — quick reference with duration
- **Zoom controls** — slider at bottom, scroll-wheel zoom
- **Responsive** — works on desktop and mobile

## Requirements

| Dependency | Version |
|-----------|---------|
| Jenkins | 2.479.3+ |
| Java | 17+ |

## Building from Source 🔧

```bash
git clone https://github.com/QAwangyang/apk-size-tracker-plugin.git
cd apk-size-tracker-plugin
mvn clean package -DskipTests
# Output: target/apk-size-tracker.hpi
```

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.2.1 | 2026-05 | ECharts bundled inline (no CDN), zero network dependency |
| 1.2.0 | 2026-05 | Persistent data cache (disk-backed, sub-ms loads) |
| 1.1.1 | 2026-05 | Chart layout fixes, diff banner, last-5 table, duration display |
| 1.1.0 | 2026-05 | Switched to doIndex() approach — fixed 404 issues |
| 1.0.0 | 2026-05 | Initial release with Jelly views |

## Roadmap 🗺️

- [ ] Threshold alerts (warn when build exceeds X MB)
- [ ] Multi-job comparison view
- [ ] Configurable max build history
- [ ] Email notifications on size regressions
- [ ] Pipeline step syntax generator

## Contributing 🤝

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -am 'Add my feature'`
4. Push to the branch: `git push origin feature/my-feature`
5. Open a Pull Request

## License 📄

This project is licensed under the Apache License, Version 2.0 — see the [LICENSE](LICENSE) file for details.
