# DNC Protocol

## Overview

DNC Protocol is a peer-to-peer messaging application designed to work over decentralized networks using both Bluetooth Low Energy (BLE) and WiFi Aware technologies. It enables direct device-to-device communication without requiring traditional internet connectivity or centralized servers.

The application leverages multiple communication channels to ensure reliable connectivity in various situations, automatically failing over from one technology to another when needed.

## Key Features

- **Peer-to-Peer Communication**: Direct device-to-device messaging without internet connectivity
- **Multi-Channel Connectivity**: Uses both WiFi Aware and Bluetooth Low Energy
- **Auto-Discovery**: Automatically finds nearby devices running the app
- **Automatic Failover**: Switches between communication methods when one becomes unavailable
- **Resilient Connection Management**: Handles connection errors with automatic retry mechanisms

## Technical Implementation

### Communication Technologies

#### Bluetooth Low Energy (BLE)
- Device discovery through name prefixes ("DNC-")
- GATT server and client implementation for bidirectional communication
- Automatic reconnection on connection loss
- Power-efficient device discovery

#### WiFi Aware
- Direct peer discovery without WiFi infrastructure
- Higher bandwidth than BLE for message transfer
- Built-in session management and recovery

### Architecture

- **Service-Oriented**: Core functionality runs as a background Android service
- **Multi-Protocol**: Simultaneously uses both BLE and WiFi Aware for optimal connectivity
- **Reactive Programming**: Uses Kotlin Flows for reactive state management
- **Permission Management**: Comprehensive permission handling for different Android versions

## Requirements

- Android 8.0 (API level 26) or higher
- Device with Bluetooth Low Energy support
- Device with WiFi Aware support (for full functionality)
- Required permissions:
  - Location (required for BLE scanning)
  - Bluetooth permissions (BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT on Android 12+)
  - WiFi Aware permissions (NEARBY_WIFI_DEVICES on Android 13+)

## Getting Started

1. Clone the repository
2. Open the project in Android Studio
3. Build and run on a compatible device
4. Grant the necessary permissions when prompted

## Usage

The app consists of several main screens accessible via the bottom navigation bar:

1. **Chat**: View and send messages to discovered peers
2. **Peers**: View currently connected peers and their status
3. **Settings**: Configure application settings

## Permissions

The app requires several permissions to function properly:

- **Location**: Required for BLE scanning and WiFi Aware
- **Bluetooth**: Required for device discovery and communication
- **WiFi Aware**: Required for direct peer-to-peer messaging
- **Notifications**: (Android 13+) For displaying message notifications

## Implementation Details

### Key Components

- **BleManager**: Handles Bluetooth Low Energy communication
- **WifiAwareManager**: Manages WiFi Aware discovery and communication
- **P2PChatService**: Core service that coordinates both communication channels
- **PermissionManager**: Handles permission requests and checks

### Communication Flow

1. **Initialization**: Both BLE and WiFi Aware are initialized
2. **Discovery**: Devices advertise their presence and scan for others
3. **Connection**: When a peer is discovered, a connection is established
4. **Messaging**: Messages are sent primarily through WiFi Aware if available
5. **Failover**: If WiFi Aware fails, communication falls back to BLE

## Building and Development

The project uses standard Android build tools. 

```bash
# Build the project
./gradlew build

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## License

© Ivelosi Technologies. All rights reserved.