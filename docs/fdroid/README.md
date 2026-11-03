# F-Droid submission

F-Droid publishes the GitHub version ("TV Remote", `com.kbs.tvremote`, `full` flavor) under the
`NonFreeNet` anti-feature, because its YouTube features use YouTube's services. F-Droid builds it
from source and signs it with its own key, so it cannot update over the debug-signed GitHub APK
(or the reverse); users choose one source.

The store text, icon, feature graphic, screenshots and per-version changelogs come from
[`fastlane/metadata/android/en-US`](../../fastlane/metadata/android/en-US). Add
`changelogs/<versionCode>.txt` for every release.

## Verified

[`com.kbs.tvremote.yml`](com.kbs.tvremote.yml) passed `fdroid lint` and `fdroid rewritemeta`, and a
local `fdroid build` (fdroidserver 2.4.5) of commit `bdbe4bd` built `com.kbs.tvremote_4.apk`
with a clean source scan and no dependency-info block. F-Droid strips the debug signing config
by itself; no `prebuild` step is needed.

## Submitting

1. Tag the release that passed the TV test (`git tag v0.4.0 && git push origin v0.4.0`). The file
   builds from that tag; the version code must match `android/app/build.gradle.kts`.
2. Fork https://gitlab.com/fdroid/fdroiddata on GitLab, add this file as
   `metadata/com.kbs.tvremote.yml` on a new branch, and open a merge request titled
   "New app: TV Remote". Fill in the template's checklist; CI rebuilds the app.
3. Answer reviewer questions on the merge request. After merge, the app normally appears in the
   F-Droid client within a few days.

Afterwards, `UpdateCheckMode: Tags` and `AutoUpdateMode: Version` let F-Droid pick up each new
`vX.Y.Z` tag automatically. Increase `versionCode` for every tag.
