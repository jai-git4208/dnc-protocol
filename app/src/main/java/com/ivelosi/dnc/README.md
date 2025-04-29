# DNC Implementation Notes

## Layer 1: Signal Filtration Implementation

### Adaptive Scanning Frequency Algorithm

The adaptive scanning frequency algorithm was implemented according to section 2.1.2 of the DNC whitepaper. This implementation:

1. **Adapts scanning intervals dynamically** based on:
   - Battery level and charging status
   - Previous scan success rates
   - Time of day (reduces frequency during typical inactivity periods)
   - Network density (number of discovered devices)

2. **Energy Optimization Features**:
   - Extends scan intervals when battery is low
   - Reduces scanning frequency when in power save mode
   - Increases frequency when charging
   - Adjusts scanning based on previous discovery success rate

3. **Implementation Details**:
   - Scanning interval bounds: 30 seconds to 15 minutes
   - Default interval: 2 minutes
   - Low/medium battery thresholds: 20%/50% 
   - Success rate thresholds for adjustment: 20%/70%
   - Time-of-day adjustments for night hours (11 PM - 6 AM)

### Energy-Efficient Signal Processing

The energy-efficient signal processing implementation optimizes device discovery by:

1. **Batch Processing**:
   - Groups incoming signals to reduce CPU wake-ups
   - Uses a queue to process multiple devices at once
   - Maintains a processing delay to capture more signals per processing cycle

2. **Signal Strength Filtering**:
   - Dynamically adjusts RSSI thresholds based on environment
   - Skips processing of weak signals
   - Considers network density when adjusting thresholds

3. **Device Caching**:
   - Maintains a cache of previously seen devices
   - Reduces redundant processing
   - Implements expiry mechanism for cached devices

4. **Power Management**:
   - Uses wake locks only during actual processing
   - Sets timeouts to prevent wake lock leaks
   - Processes signals in background thread to avoid UI blocking

## Background Service Implementation

### DNCBackgroundService

A foreground service has been implemented to ensure DNC operates reliably even when the app is in the background:

1. **Persistent Operation**:
   - Runs as a foreground service with a persistent notification
   - Maintains core functionality when the app is minimized or the screen is off
   - Uses wake locks to prevent the system from prematurely stopping critical operations

2. **Core Operation Migration**:
   - Moved Bluetooth scanning from MainActivity to service
   - Transferred connection management and message handling to background processing
   - Implemented proper binding mechanism for UI/service communication

3. **Service Lifecycle Management**:
   - Graceful startup/shutdown procedures
   - Proper resource cleanup on service termination
   - Binding mechanism for UI components to interact with the service

## Notification System

### DNCNotificationManager

A comprehensive notification system has been implemented to:

1. **Service Status Notifications**:
   - Persistent foreground service notification
   - Status updates for service operations
   - Actionable buttons for starting/stopping scanning and service

2. **Discovery and Connection Notifications**:
   - Device discovery notifications
   - Connection status updates
   - Signal processing information

3. **Communication Notifications**:
   - Message notifications
   - File transfer status updates
   - Command execution alerts

### DNCNotificationReceiver

A broadcast receiver to handle notification actions:
   - Responds to scan button clicks from notifications
   - Handles service stop requests
   - Routes other notification actions to appropriate components

## Component Integration

All core DNC components have been updated to integrate with the new background service and notification system:

1. **AdaptiveScanningManager**:
   - Now sends notifications about scanning status
   - Integrates with the background service for continuous operation
   - Maintains scanning state even when the UI is not visible

2. **EnergyEfficientSignalProcessor**:
   - Posts notifications for significant signal processing events
   - Handles permission checks for modern Android versions
   - Operates efficiently in the background service context

3. **SocketCommunicator & FileReceiver**:
   - Now send notifications for connection events
   - Provide updates on file transfer progress
   - Alert users about received messages and commands

4. **NetworkManager**:
   - Integrated with notifications for connection status
   - Works through the background service for persistent connections
   - Maintains socket connections in the background

## Integration with Main Application

The MainActivity has been enhanced to:

1. Use adaptive scanning when optimal conditions are met (good battery, charging, etc.)
2. Fall back to traditional scanning when conditions aren't ideal
3. Track and use signal strength statistics to optimize discovery
4. Properly clean up resources when the application is destroyed
5. Bind to the background service for UI updates and control

## Manifest Updates

The AndroidManifest.xml has been updated with:

1. **New Permissions**:
   - FOREGROUND_SERVICE for the background service
   - WAKE_LOCK for maintaining processing during device sleep
   - POST_NOTIFICATIONS for the notification system
   - FOREGROUND_SERVICE_DATA_SYNC for data operations

2. **Service and Receiver Registration**:
   - DNCBackgroundService registration with appropriate attributes
   - DNCNotificationReceiver declaration with intent filters

These implementations complete all items in Layer 1 of the DNC whitepaper's requirements and establish a solid foundation for the efficient discovery and processing of nearby DNC nodes. The background service and notification system ensure that DNC can operate reliably in real-world conditions even when not in the foreground.