# TCP Client–Server System Design Document

## 1. Overview

### 1.1 Problem Statement

The objective of this system is to design and implement a TCP-based client–server architecture that enables multiple clients to communicate with a centralized server. Clients establish a TCP connection with the server, send requests using a defined protocol, and receive corresponding responses.

The system must support multiple concurrent client connections, provide reliable communication using TCP, and define a clear application-level protocol for request and response messages. The design should ensure robustness against malformed input, partial messages, and connection failures while maintaining efficient request processing.

---

## 1.2 Goals

1. Enable reliable communication between clients and server using TCP.
2. Allow the server to handle multiple concurrent client connections.
3. Define a structured application-level protocol for request and response messages.
4. Ensure efficient request processing with low latency.
5. Manage the full lifecycle of connections including connection establishment and termination.
6. Provide an architecture that allows future extensions such as authentication, encryption, and load balancing.

---

## 1.3 Non-Goals

1. Persistent storage of requests or responses.
2. Authentication and authorization mechanisms.
3. Support for protocols other than TCP (e.g., UDP).
4. Distributed server clusters in the initial version.

---

# 2. Functional Requirements

### Client Requirements

* Client must establish a TCP connection with the server.
* Client must send requests following the defined protocol.
* Client must receive and parse responses from the server.
* Client must handle connection failures and server disconnects.

### Server Requirements

* Server must listen on a configurable TCP port.
* Server must accept multiple concurrent client connections.
* Server must process incoming requests and return appropriate responses.
* Server must detect and handle client disconnections.
* Server must validate incoming messages.

---

# 3. Non-Functional Requirements

### Performance

* Server must support at least **1000 concurrent connections**.
* Average request latency should remain **below 50 ms** under normal load.

### Scalability

* Architecture should support horizontal scaling via load balancing in future versions.

### Reliability

* Server must not crash due to malformed messages.
* System must recover gracefully from connection drops.

### Observability

* System should log connection events, errors, and request processing information.

---

# 4. High-Level Architecture

The system follows a standard client–server architecture.

Components:

* Client Application
* TCP Network Layer
* Server Listener
* Connection Handler
* Worker Thread Pool
* Request Processing Module

```
+-----------+       TCP       +-------------+
|  Client   | <-------------> |   Server    |
+-----------+                 +-------------+
                                     |
                              Accept Connections
                                     |
                              +---------------+
                              | Worker Pool   |
                              +---------------+
                                     |
                              Request Processing
```

### Component Description

**Client**

Responsible for establishing TCP connection, sending requests, and receiving responses.

**Server Listener**

Listens on a specified port and accepts incoming TCP connections.

**Connection Handler**

Manages communication with connected clients.

**Worker Pool**

Handles concurrent request processing.

**Request Processor**

Contains the business logic that processes client requests.

---

# 5. API / Interface Design

### Client Interface

```
connect(serverAddress, port)
sendRequest(request)
receiveResponse()
disconnect()
```

Example usage:

```
client.connect("127.0.0.1", 8080)

request = {
  type: "PING",
  payload: "Hello Server"
}

client.sendRequest(request)
response = client.receiveResponse()

client.disconnect()
```

---

### Server Interface

```
startServer(port)
acceptConnection()
handleClientConnection(socket)
processRequest(request)
sendResponse(response)
```

---

## 6. Protocol Design (Wire Format)

Since TCP is a **stream-oriented protocol**, it does not preserve message boundaries. Therefore, the application protocol must define a clear message framing strategy and message structure.

This system uses a **length-prefixed message format** so that receivers can correctly determine where each message begins and ends.

---

### 6.1 Message Structure

Each message transmitted over the TCP connection follows the structure below:

```text
| Length (4 bytes) | Version (1 byte) | Type (1 byte) | Payload (N bytes) |
```
Where length is number of bytes following the length field. 

Length = size(payload).

| Field   | Size    | Description                                      |
| ------- | ------- | ------------------------------------------------ |
| Length  | 4 bytes | Length of the remaining message after this field |
| Version | 1 byte  | Protocol version used to interpret the message   |
| Type    | 1 byte  | Message type identifier                          |
| Payload | N bytes | Message-specific data                            |

### 6.1.1 Maximum Message Size

Although the Length field is 4 bytes, the server enforces a **maximum allowed payload size** to prevent memory exhaustion attacks.

Configuration:

| Parameter      | Value |
| -------------- | ----- |
| maxPayloadSize | 1 MB  |

### Validation Rule

When a message header is received:

1. Read the Length field.
2. If `Length > maxPayloadSize + headerSize`, the message is rejected.

### Handling Oversized Messages

If a client sends a message exceeding the allowed size:

1. The server sends an **ERROR** message.
2. The server closes the connection.

Example:

```
ERROR
{
  "errorCode": 413,
  "message": "Payload too large"
}
```

### Rationale

Allowing arbitrarily large messages could cause a malicious client to force the server to allocate gigabytes of memory. A fixed upper bound protects the server against such attacks.

### 6.1.2 Byte Order

All multi-byte integer fields in the protocol are encoded using **network byte order (big-endian)**.

This ensures consistent interpretation across different hardware architectures.

Example:

If the Length field contains the integer **256**, it will be encoded as:

```
00 00 01 00
```

Using network byte order aligns the protocol with standard internet conventions used by protocols such as TCP/IP and HTTP.

---

### 6.2 Protocol Versioning

The **Version field** allows the protocol to evolve while maintaining backward compatibility.

Initial protocol version:

```
Version: 1
```

When receiving a message:

* If the version is **supported**, the message is processed normally.
* If the version is **unsupported**, the receiver sends an **ERROR** message and may close the connection.

Example error response:

```json
{
  "errorCode": 426,
  "message": "Unsupported protocol version"
}
```

Future versions may introduce:

* New message types
* Extended headers
* Additional payload formats

---

### 6.3 Message Types

The protocol defines several message types to support request processing, connection lifecycle management, error handling, and liveness checks.

| Type ID | Message Name    | Direction       | Description                                     |
|---------|-----------------|-----------------|-------------------------------------------------|
| 1       | PING            | Client → Server | Liveness check to verify server availability    |
| 2       | PONG            | Server → Client | Response to a PING message                      |
| 3       | REQUEST         | Client → Server | Client request containing application operation |
| 4       | RESPONSE        | Server → Client | Response to a client request                    |
| 5       | ERROR           | Server → Client | Indicates malformed request or processing error |
| 6       | CLOSE           | Client → Server | Client requests graceful connection termination |
| 7       | SERVER_CLOSE    | Server → Client | Server requests connection termination          |
| 8       | CLOSE_ACK       | Both Directions | Acknowledges receipt of a CLOSE message         |
| 9       | HEARTBEAT       | Both directions | Keepalive message for idle connections          |
| 10      | SERVER_SHUTDOWN | Server → Client | Indicates server is shutting down gracefully    |

---

### 6.4 Message Examples

#### Ping Message

Client checks server availability.

```
Length: 2
Version: 1
Type: PING
Payload: none
```

Server responds:

```
Length: 2
Version: 1
Type: PONG
Payload: none
```

---

#### Request Message

Example payload:

```json
{
  "operation": "GET_USER",
  "userId": 123
}
```

---

#### Response Message

Example response:

```json
{
  "status": "OK",
  "data": {
    "name": "John",
    "age": 30
  }
}
```

---

#### Error Message

If the server receives an invalid request, it sends an error message.

```json
{
  "errorCode": 400,
  "message": "Malformed request"
}
```

---

### 6.5 Message Framing Strategy

Because TCP delivers a continuous stream of bytes, messages may arrive in fragments or may be combined together.

The receiver must implement the following parsing strategy:

1. Read **4 bytes** to determine the message length.
2. Read **Version** and **Type** fields.
3. Continue reading until the full payload is received.
4. Parse the message based on the **Type field**.

### 6.5.1 Incomplete Message Protection (Slow-Send Defense)

A client may send a valid message header indicating a payload length but fail to transmit the full payload.

Example attack scenario:

```
Client sends Length = 500
Client sends 200 bytes
Client stops sending
Connection remains open
```

The server's event loop would hold a partial buffer waiting for the remaining 300 bytes indefinitely. This behavior can be exploited as a **Slowloris-style application-layer attack**, where many connections hold partially received messages and exhaust server resources.

To mitigate this, the server implements a **message receive timeout tied to the framing state machine**.

---

### Message Receive Timer

When the server reads a valid message header:

1. The server records the **start timestamp** of message reception.
2. The server expects the full payload to arrive within a configurable time window.

Configuration:

| Parameter             | Value      |
| --------------------- | ---------- |
| messageReceiveTimeout | 15 seconds |

---

### Framing State Machine

The event loop maintains the following states for each connection:

```
WAIT_HEADER -> WAIT_PAYLOAD -> MESSAGE_COMPLETE
```

Behavior:

**WAIT_HEADER**

* Read 4-byte length field.
* Validate message size.

**WAIT_PAYLOAD**

* Start the **messageReceiveTimer**.
* Accumulate payload bytes in the buffer.

If:

```
received_bytes == expected_length
```

transition to:

```
MESSAGE_COMPLETE
```

---

### Timeout Handling

If the full payload is not received within `messageReceiveTimeout`:

1. The server sends an **ERROR message**.
2. The server closes the connection.

Example:

```
ERROR
{
  "errorCode": 408,
  "message": "Incomplete message payload"
}
```

---

### Rationale

This mechanism ensures that a client cannot hold a connection indefinitely by sending only part of a message. By enforcing a deadline on message completion, the server prevents Slowloris-style attacks that attempt to exhaust memory or connection capacity.

---

### 6.6 Keepalive Strategy

HEARTBEAT messages are used to detect broken or idle connections.

### Heartbeat Initiation

The **server initiates heartbeat checks**.

If no messages are received from a client within:

```
heartbeatInterval = 30 seconds
```

the server sends a HEARTBEAT message.

### Heartbeat Response

When a client receives a HEARTBEAT message it must immediately respond with a HEARTBEAT message.

Example:

```
Server -> HEARTBEAT
Client -> HEARTBEAT
```

### Failure Detection

If the server does not receive a heartbeat response after:

```
maxMissedHeartbeats = 3
```

the connection is considered dead.

The server then closes the connection.

### Rationale

This mechanism allows the server to detect:

* crashed clients
* broken network connections
* idle connections consuming resources


### 6.7 Graceful Server Shutdown

A graceful shutdown ensures that the server terminates without abruptly dropping active client connections or losing in-flight requests.

#### Shutdown Trigger

Graceful shutdown is initiated when the server receives a termination signal such as:

* `SIGTERM`
* `SIGINT`
* Administrative shutdown command

Upon receiving the signal, the server enters **shutdown mode**.

---

#### Shutdown Strategy

The shutdown process follows three phases:

1. **Stop Accepting New Connections**
2. **Drain Existing Connections**
3. **Terminate Remaining Connections**

---

#### Phase 1: Stop Accepting New Connections

Immediately after receiving the shutdown signal:

* The server stops accepting new TCP connections.
* The listening socket is closed or removed from the event loop.
* Any new connection attempts are rejected.

Existing client connections remain active.

---

#### Phase 2: Notify Clients and Drain Connections

The server sends a **SERVER_SHUTDOWN** message to all connected clients.

Example:

```json
{
  "type": "SERVER_SHUTDOWN",
  "reason": "server maintenance",
  "retryAfter": 30
}
```

Client behavior:

* Clients should stop sending new requests.
* Clients may finish any in-progress operations.
* Clients initiate a graceful close using the `CLOSE` message.

Example flow:

```text
Server -> SERVER_SHUTDOWN
Client -> CLOSE
Server -> CLOSE_ACK
Connection closed
```

The server continues processing any **in-flight requests** before closing the connection.

---

#### Phase 3: Shutdown Timeout

A configurable **shutdown timeout** is used to prevent the shutdown from blocking indefinitely.

Example configuration:

| Parameter           | Description                                    |
| ------------------- | ---------------------------------------------- |
| shutdownGracePeriod | Maximum time to wait for clients to disconnect |
| default value       | 30 seconds                                     |

If clients do not disconnect within the grace period:

* The server forcefully closes remaining connections.

---

#### Example Scenario

Assume the server has **50 active client connections** and receives `SIGTERM`.

Shutdown sequence:

```text
SIGTERM received
        |
        v
Server stops accepting new connections
        |
        v
Server sends SERVER_SHUTDOWN to all 50 clients
        |
        v
Clients finish in-flight requests
        |
        v
Clients send CLOSE
        |
        v
Server replies CLOSE_ACK
        |
        v
Connections close gracefully
```

After the grace period expires:

* Any remaining connections are forcefully terminated.
* Server process exits.

---

#### Benefits of Graceful Shutdown

This approach ensures:

* In-flight requests complete successfully
* Clients are informed about server shutdown
* No abrupt connection resets occur
* Predictable system behavior during deployments or maintenance

### 6.8 Server-Initiated Close

The server may need to terminate a client connection for several reasons:

* idle timeout
* malformed messages
* resource pressure
* protocol violations

In such cases the server sends a **SERVER_CLOSE** message.

Example payload:

```
{
  "reason": "idle timeout"
}
```

Connection termination flow:

```
Server -> SERVER_CLOSE
Client -> CLOSE
Server -> CLOSE_ACK
Connection closed
```

### 6.9 Connection Close Handshake

Connection termination follows a **two-step handshake** to ensure both peers agree to close the connection.

#### Client-Initiated Close

```
Client -> CLOSE
Server -> CLOSE_ACK
Connection closed
```

#### Server-Initiated Close

```
Server -> SERVER_CLOSE
Client -> CLOSE
Server -> CLOSE_ACK
Connection closed
```

If either side does not receive `CLOSE_ACK` within **5 seconds**, the connection is forcefully terminated.


If the client does not respond within a short timeout (5 seconds), the server forcefully closes the socket.

# 7. Connection Lifecycle

The sequence of events during communication is as follows:

```
Client -> Establish TCP connection
Server -> Accept connection
Client -> Send request
Server -> Parse request
Server -> Process request
Server -> Send response
Client -> Receive response
Client -> Close connection
```

Connections may remain open for multiple requests.

---

# 8. Concurrency and Connection Management

The server uses an **event-driven networking model combined with a worker thread pool**.
This architecture separates **network I/O** from **request processing** to efficiently handle thousands of concurrent connections while preventing worker threads from blocking on slow or idle clients.

---

## 8.1 Architecture Overview

The system consists of three primary components:

| Component          | Responsibility                                    |
| ------------------ | ------------------------------------------------- |
| Listener Thread    | Accept new TCP connections                        |
| Event Loop Threads | Manage socket I/O, buffering, and message framing |
| Worker Thread Pool | Execute application request logic                 |

### Architecture Diagram

```
               +------------------+
               |   Listener       |
               +------------------+
                        |
                        v
              +--------------------+
              | Event Loop Threads |
              | (epoll/select)     |
              +--------------------+
                        |
               Parse framed requests
                        |
                        v
                +---------------+
                | Worker Pool   |
                +---------------+
                        |
                 Process request
                        |
                        v
               Response returned to
                event loop thread
```

---

## 8.2 Connection Lifecycle

1. The **listener thread** accepts a new TCP connection.

2. The connection is registered with an **event loop thread** using a system facility such as:

    * `epoll` (Linux)
    * `kqueue` (BSD/macOS)
    * `select` or `poll`

3. The event loop thread performs:

    * non-blocking `read()`
    * message buffering
    * protocol framing
    * request parsing

4. When a complete request is received:

    * the request is submitted to the **worker thread pool**.

5. The worker thread:

    * executes the request
    * generates a response

6. The response is returned to the **event loop thread**, which performs the socket `write()`.

---

## 8.3 Event Loop Responsibilities

Event loop threads manage all network I/O and connection state.

Responsibilities include:

* monitoring sockets for readability/writability
* reading incoming bytes
* assembling messages using the framing protocol
* validating message headers
* dispatching parsed requests to worker threads
* sending responses to clients

Because event loops use **non-blocking sockets**, they are never blocked by slow clients.

Typical configuration:

| Parameter        | Example       |
| ---------------- | ------------- |
| eventLoopThreads | 2 × CPU cores |

---

## 8.4 Worker Thread Pool

Worker threads are responsible **only for application-level processing**.

Responsibilities:

* executing request handlers
* performing business logic
* generating response messages

Worker threads **never perform blocking network I/O**.

Example configuration:

| Parameter        | Example             |
| ---------------- | ------------------- |
| workerThreads    | number of CPU cores |
| requestQueueSize | 10,000              |

When worker threads are fully utilized:

1. requests are placed in a bounded queue
2. if the queue is full, new requests are rejected with an **ERROR response**

This mechanism provides **backpressure** and prevents memory exhaustion.

---

## 8.5 Maximum Connection Limit

The server enforces a limit on concurrent connections.

Example configuration:

| Parameter      | Value |
| -------------- | ----- |
| maxConnections | 2000  |

When this limit is reached:

* new connection attempts are rejected
* the server may immediately close the socket

This protects the server from connection exhaustion attacks.

---

## 8.6 Idle Connection Handling

A client may connect but send no data, potentially consuming resources indefinitely.

To prevent this, the server enforces an **idle timeout**.

Configuration:

| Parameter   | Value      |
| ----------- | ---------- |
| idleTimeout | 60 seconds |

If no data is received during this interval:

1. the server sends a **SERVER_CLOSE** message
2. the connection is closed.

Example flow:

```
Client connects
Client sends no data
Idle timeout expires
Server -> SERVER_CLOSE
Connection closed
```

---

## 8.7 Slow Client Defense

Slow clients may attempt to exhaust resources by sending data very slowly.

Mitigation strategies include:

| Threat               | Mitigation           |
| -------------------- | -------------------- |
| Slow reads           | Non-blocking I/O     |
| Idle connections     | Idle timeout         |
| Too many connections | maxConnections limit |
| Excessive requests   | bounded worker queue |

These safeguards prevent a small number of malicious clients from degrading system performance.

---

## 8.8 Backpressure Strategy

When system load increases:

1. requests accumulate in the worker queue
2. once the queue reaches capacity:

    * new requests are rejected
    * the server sends an **ERROR response**

Example:

```
ERROR
{
  "errorCode": 503,
  "message": "Server overloaded"
}
```

This ensures the server remains stable even under heavy load.

---

## 8.9 Benefits of the Architecture

This architecture provides several advantages:

* prevents worker threads from blocking on idle clients
* supports thousands of concurrent connections
* isolates network I/O from request processing
* protects the system from resource exhaustion
* enables predictable performance under load

# 9. Failure Scenarios

### Client Disconnect

Scenario:
Client closes the connection unexpectedly.

Handling:

* Server detects EOF or socket close.
* Server releases associated resources.

---

### Partial Messages

Scenario:
TCP delivers only part of a message.

Handling:

* Maintain a receive buffer.
* Continue reading until full message length is received.

---

### Malformed Requests

Scenario:
Client sends invalid message format.

Handling:

* Server returns an error response.
* Connection may be closed if protocol violation occurs.

---

### Server Overload

Scenario:
Too many incoming connections.

Handling:

* Limit maximum connections.
* Reject or queue new connections.

---

### Network Timeout

Scenario:
Idle connections consume resources.

Handling:

* Close connections inactive for a configured timeout.

---

# 10. Configuration

| Parameter       | Description                               |
| --------------- | ----------------------------------------- |
| Server Port     | TCP port used by server                   |
| Max Connections | Maximum allowed concurrent clients        |
| Idle Timeout    | Maximum allowed idle time for connections |
| Worker Threads  | Number of worker threads                  |

---
# 11. Logging and Monitoring

### Logging

Events that should be logged:

* Client connection and disconnection
* Request processing
* Errors and protocol violations

### Metrics

Track:

* Active connections
* Request throughput
* Error rate
* Average response latency

---

# 12. Testing Strategy

### Unit Tests

* Message serialization and parsing
* Protocol validation

### Integration Tests

* Client-server communication
* Multiple concurrent clients

### Load Testing

* Simulate large number of concurrent connections
* Measure latency and throughput

---

# 13. Future Improvements

Possible enhancements include:

* TLS encryption for secure communication
* Authentication and authorization
* Load balancing across multiple servers
* Message compression
* Rate limiting
* Persistent storage support
