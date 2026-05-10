# EasyGallery — Claude Reference

## Project overview

Android photo gallery app with AI-powered search and analysis:
- **Semantic search** via CLIP (image + text embeddings)
- **OCR search** via ML Kit text recognition
- **Object detection** via YOLOv8s ONNX
- **Face detection + embedding** via ML Kit + MobileFaceNet ONNX
- **Map view** of geotagged photos via OSMDroid
- **People tab** showing contacts with photos

## Build, install, run

```bash
# Build
./gradlew assembleDebug

# Install (phone must be connected via ADB)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.example.easygallery/.MainActivity

# One-liner after edits
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.example.easygallery/.MainActivity
```

**Always build after every code change before reporting done.**

## Logcat debugging

```bash
# All app logs
adb logcat -s EasyGallery:* | grep com.example.easygallery

# Specific tags used in this project
adb logcat -s FaceIndexManager FaceEncoder FaceDetector YoloDetector OcrManager EmbeddingManager ModelManager MapFragment

# Clear log then watch
adb logcat -c && adb logcat | grep com.example.easygallery
```

## Key files

### UI / Navigation
| File | Purpose |
|---|---|
| `MainActivity.kt` | ViewPager2 + TabLayout host. `offscreenPageLimit=4` keeps all fragment views alive. Tab clicks use `setCurrentItem(pos, false)` (no animation). |
| `GalleryPagerAdapter.kt` | 5 tabs: Gallery(0), Search(1), Map(2), Objects(3), People(4) |
| `SettingsActivity.kt` | All feature toggles + model download/progress UI |
| `activity_settings.xml` | Settings layout (scroll view with sections per feature) |

### Fragments
| File | Purpose |
|---|---|
| `GalleryFragment.kt` | Grid of all images, folder grouping, column slider via prefs |
| `SearchFragment.kt` | CLIP semantic search + OCR text search + object label search |
| `MapFragment.kt` | OSMDroid map with clustered markers for geotagged photos |
| `ObjectBrowseFragment.kt` | Grid of detected object categories |
| `PeopleFragment.kt` | Contacts with photos (ContactsContract.Contacts.PHOTO_URI) |

### AI pipeline
| File | Purpose |
|---|---|
| `ModelManager.kt` | CLIP image encoder model download/state (`clip_image_encoder.onnx`) |
| `ClipEncoder.kt` | CLIP image encoder inference (224×224 NCHW) |
| `ClipTextEncoder.kt` | CLIP text encoder inference, pre-warmed on app start |
| `ClipTokenizer.kt` | BPE tokenizer for CLIP text input |
| `EmbeddingManager.kt` | Indexes all images with CLIP embeddings, LiveData progress |
| `YoloModelManager.kt` | YOLOv8s model download/state (`yolov8s.onnx`, ~45 MB) |
| `YoloDetector.kt` | YOLOv8s object detection inference |
| `ObjectDetectionManager.kt` | Indexes all images with YOLO labels, LiveData progress |
| `OcrManager.kt` | ML Kit OCR indexing of all images, LiveData progress |
| `FaceModelManager.kt` | MobileFaceNet model download/state (`w600k_mbf.onnx`, ~13 MB) |
| `FaceDetector.kt` | ML Kit face detection → crops per face |
| `FaceEncoder.kt` | MobileFaceNet ONNX inference → 512-dim L2-normalized embedding |
| `FaceIndexManager.kt` | Indexes all images with face embeddings, LiveData progress |

### Data
| File | Purpose |
|---|---|
| `VectorStore.kt` | SQLite DB (`embeddings.db`, v4). Tables: `embeddings`, `ocr`, `objects`, `faces` |
| `GalleryViewModel.kt` | Loads all image paths from MediaStore, exposes `loaded` LiveData |

### Sheets / dialogs
| File | Purpose |
|---|---|
| `ImageInfoSheet.kt` | Bottom sheet: EXIF info, GPS coords, OCR text, object labels |
| `ClusterImagesSheet.kt` | Bottom sheet: grid of images in a map cluster (max 60% screen height) |

## Database schema (VectorStore, DB_VERSION=4)

```sql
embeddings (hash TEXT PK, path TEXT, embedding BLOB)        -- CLIP embeddings
ocr        (hash TEXT PK, path TEXT, ocr_text TEXT)         -- OCR text
objects    (hash TEXT PK, path TEXT, labels TEXT)           -- comma-separated YOLO labels
faces      (id INTEGER PK AUTOINCREMENT, path TEXT,
            face_index INTEGER, embedding BLOB)             -- MobileFaceNet embeddings
-- face_index = -1 is a sentinel: image processed, no faces found
-- UNIQUE INDEX on (path, face_index)
```

When bumping DB_VERSION, add migration in `onUpgrade`.

## Models and storage

All models stored in `context.filesDir` (app-private, not cleared by system):
- `clip_image_encoder.onnx` — CLIP image encoder
- `clip_text_encoder.onnx` — CLIP text encoder  
- `yolov8s.onnx` — YOLOv8s object detection
- `w600k_mbf.onnx` — MobileFaceNet face embedding

SharedPrefs key `gallery_prefs`:
- `columns` — gallery grid column count
- `clip_search_enabled` — CLIP feature on/off
- `ocr_enabled` — OCR feature on/off
- `object_detection_enabled` — YOLO feature on/off
- `face_detection_enabled` — face feature on/off

## Permissions (AndroidManifest.xml)

```
READ_MEDIA_IMAGES, READ_MEDIA_VIDEO (API 33+)
READ_EXTERNAL_STORAGE (API < 33)
ACCESS_MEDIA_LOCATION   ← required for unredacted GPS EXIF on Android 10+
READ_CONTACTS           ← People tab
INTERNET                ← model downloads
ACCESS_NETWORK_STATE
WRITE_EXTERNAL_STORAGE  ← OSMDroid tile cache
```

## GPS / EXIF note

Android 10+ redacts GPS from EXIF unless `ACCESS_MEDIA_LOCATION` is granted **and** you use `ExifInterface(filePath)` directly (not via stream from URI). `ImageInfoSheet.kt` uses the file path directly for this reason.

## OSMDroid map notes

- `Configuration.getInstance().load(context, sharedPrefs)` must be called to set tile cache dir
- `RadiusMarkerClusterer` subclass overrides `zoomOnCluster` as no-op to prevent auto-zoom on cluster tap
- `isVerticalMapRepetitionEnabled = false` prevents multiple world copies vertically
- `offscreenPageLimit = 4` on ViewPager2 keeps map fragment alive across tab switches

## Dependencies (libs.versions.toml)

Notable additions beyond standard Android:
- `coil` — image loading
- `onnxruntime-android` — ONNX model inference
- `mlkit-text-recognition` — OCR
- `mlkit-face-detection` — face detection
- `kotlinx-coroutines-play-services` — `await()` for ML Kit Tasks
- `osmdroid` + `osmbonuspack` (via jitpack) — map + clustering
- `androidx-exifinterface` — EXIF reading
