# DNC (Device Network Communication)

## Overview
DNC (Device Network Communication) is an Android application that enables secure device-to-device communication using a combination of Bluetooth for discovery and WiFi for data transmission. It allows seamless file transfers, text messaging, and remote command execution between compatible Android devices on the same network.

## Features
- Bluetooth device discovery with DNC-prefix filtering
- WiFi-based socket communication for reliable data transfer
- Text messaging between connected devices
- File transfer capabilities with progress tracking
- Remote command execution (ping, device info, restart, shutdown)
- Custom command support for extensibility
- Comprehensive logging system

## Architecture
The application follows a modular architecture with clear separation of concerns:

- **Network Layer**: Handles Bluetooth discovery and WiFi socket communication
- **Protocol Layer**: Defines the message format and command structure
- **UI Layer**: Provides user interface for device discovery, connection, and messaging

### Key Components
- `NetworkManager`: Core component handling socket connections and message routing
- `SocketCommunicator`: Manages bidirectional communication over established sockets
- `MessageProtocol`: Defines the protocol specification for inter-device communication
- `FileReceiver`: Handles incoming file transfers and reconstruction
- `MessageHandlingUI`: UI component for message composition and transmission

## Getting Started

### Prerequisites
- Android Studio 4.0+
- Android SDK 23+
- Bluetooth and WiFi capabilities on test devices

### Setup
1. Clone this repository
2. Open the project in Android Studio
3. Build and run on an Android device (emulators may have limited Bluetooth support)

### Usage
1. Launch the application on two or more devices
2. Set device names to start with "DNC-" prefix using the "Set Device Name" button
3. Scan for other DNC devices using the "Scan" button
4. Connect to discovered devices
5. Send messages, files or commands to connected devices

## Message Protocol
DNC uses a simple pipe-delimited protocol for all communications:
```
TYPE|TIMESTAMP|PAYLOAD
```

### Message Types
- `HANDSHAKE`: Initial connection establishment
- `HEARTBEAT`: Connection maintenance
- `TEXT`: Plain text messages
- `COMMAND`: Remote command execution
- `FILE_START`: Initiates file transfer
- `FILE_CHUNK`: Contains file data segment
- `FILE_END`: Completes file transfer
- `STATUS`: Status updates
- `ERROR`: Error notifications

## Security Notice
This application is designed for use in trusted environments only. It does not implement end-to-end encryption for data transmission. All communications occur over local networks and should not be considered secure for sensitive information.

## File Transfer
Files are transferred in chunks and automatically saved to the device's Downloads/DNCTransfers directory. The application handles large files efficiently by breaking them into manageable segments.

## Troubleshooting
- Ensure Bluetooth and WiFi are enabled on all devices
- Check that devices have appropriate permissions granted (Location, Bluetooth, WiFi)
- Verify that devices are on the same WiFi network
- For connection issues, check firewall settings that might block socket communication

I'll create a concise todo list section that you can add to your GitHub README based on the DNC whitepaper:

## Todo List - Future Implementations (To be done by Ivelosi Team)

- [ ] **Layer 1: Signal Filtration**
  - [ ] Implement DNC-Prefix validation for Bluetooth/WiFi signals
  - [ ] Build adaptive scanning frequency algorithm
  - [ ] Create energy-efficient signal processing

- [ ] **Layer 2: NID System**
  - [ ] Develop 10-digit Node Identifier generation
  - [ ] Implement message structure with source/destination NIDs
  - [ ] Add signature verification system

- [ ] **Layer 3: Routing Engine**
  - [ ] Create local topology mapping
  - [ ] Implement path weight calculation
  - [ ] Build ML-enhanced routing system
  - [ ] Develop adaptive TTL mechanism

- [ ] **Layer 4: Node Registry**
  - [ ] Build node registration process
  - [ ] Implement ledger update propagation
  - [ ] Add Byzantine Fault Tolerance

- [ ] **Super Node Architecture**
  - [ ] Implement heartbeat mechanism
  - [ ] Create fail-over protocol
  - [ ] Develop data anchoring and persistent storage

- [ ] **Security Implementation**
  - [ ] Add end-to-end message encryption
  - [ ] Implement authentication framework
  - [ ] Create configurable privacy levels

- [ ] **API Development**
  - [ ] Build core messaging functions
  - [ ] Create node discovery endpoints
  - [ ] Implement security operations

- [ ] **Testing & Deployment**
  - [ ] Create performance benchmarking suite
  - [ ] Develop energy efficiency testing
  - [ ] Build Super Node infrastructure

- [ ] **Documentation**
  - [ ] Add mathematical foundations section
  - [ ] Create terminology guide
  - [ ] Develop integration examples

## Legal and Confidentiality

### Copyright Notice
© Ivelosi Technologies. All rights reserved.

### Confidentiality Agreement
By accessing this documentation and source code, you agree to not share any information outside of authorized personnel. All contents are proprietary and confidential.

## License
This software is proprietary and confidential. Unauthorized copying, transfer or reproduction via any medium is strictly prohibited without the express written consent of Ivelosi Technologies.
