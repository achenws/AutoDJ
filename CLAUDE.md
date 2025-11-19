# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android pedometer application for ECE420 Lab1 that uses IMU sensors (accelerometer and gyroscope) to detect and count steps in real-time. The app visualizes sensor data using graphs and can export sensor readings to CSV files.

## Build and Development Commands

### Building the Project
```bash
# Clean and build the project
./gradlew clean build

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test
```

### Debugging
```bash
# View application logs
adb logcat -s PedometerSimple:* SensorReader:* StepDetector:*

# Grant required permissions
adb shell pm grant com.ece420.lab1 android.permission.ACTIVITY_RECOGNITION
adb shell pm grant com.ece420.lab1 android.permission.HIGH_SAMPLING_RATE_SENSORS
```

## Architecture Overview

### Core Components

1. **PedometerSimple** (`app/src/main/java/com/ece420/lab1/PedometerSimple.java`)
   - Main activity managing UI and sensor lifecycle
   - Handles runtime permissions for Android 6.0+
   - Integrates GraphView for real-time data visualization
   - Coordinates between SensorReader and UI updates

2. **SensorReader** (`app/src/main/java/com/ece420/lab1/SensorReader.java`)
   - Implements SensorEventListener for accelerometer and gyroscope
   - Manages sensor data collection at 100 Hz sample rate
   - Handles CSV file writing for data export to external storage
   - Integrates StepDetector for step counting and Resampler for data processing

3. **StepDetector** (`app/src/main/java/com/ece420/lab1/StepDetector.java`)
   - Implements threshold-based step detection algorithm
   - Maintains sliding window buffer of 11 samples
   - Uses acceleration threshold of 1.0f and minimum 0.2s between steps
   - Processes magnitude of acceleration to identify step patterns

4. **Resampler** (`app/src/main/java/com/ece420/lab1/Resampler.java`)
   - Resamples sensor data at fixed time intervals
   - Handles timestamp-based interpolation for consistent data rates

### Data Flow
1. Sensors generate events → SensorReader receives and processes them
2. Accelerometer data → StepDetector → Step count updates
3. Both accelerometer and gyroscope data → Resampler → Consistent sampling
4. Processed data → GraphView for visualization and CSV for export

### Key Design Patterns
- **Observer Pattern**: SensorEventListener for sensor updates
- **Buffering Strategy**: Fixed-size circular buffer for step detection
- **State Management**: Instance variables maintain state across sensor events
- **Separation of Concerns**: UI, sensor handling, and algorithms in separate classes

## Code Style Guidelines

- **Class naming**: PascalCase (e.g., `StepDetector`)
- **Method naming**: camelCase (e.g., `detect()`)
- **Instance variables**: Prefix with 'm' (e.g., `mSensorReader`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `N_SAMPLES`, `ACCEL_THRESHOLD`)
- **Logging**: Use TAG constants and Log.d() for debugging

## Important Technical Details

### Permissions
The app requires these permissions in AndroidManifest.xml:
- `ACTIVITY_RECOGNITION` - For step detection
- `HIGH_SAMPLING_RATE_SENSORS` - For accurate sensor readings
- `MANAGE_EXTERNAL_STORAGE` - For CSV export

### Sensor Configuration
- Sample rate: 100 Hz (SAMPLE_RATE in SensorReader)
- Step detection threshold: 1.0f acceleration magnitude
- Minimum step interval: 200ms
- Buffer size: 11 samples for step detection

### Build Configuration
- Compile SDK: 34
- Min SDK: 28 (Android 9)
- Target SDK: 29 (Android 10)
- Gradle: 8.5.1
- AndroidX enabled

## Codacy Integration
If Codacy CLI is available, analyze modified Java files:
```bash
codacy_cli_analyze --rootPath . --file app/src/main/java/com/ece420/lab1/[FileName].java
```