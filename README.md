# Krossbow

[![Bintray Download](https://img.shields.io/bintray/v/joffrey-bion/maven/krossbow-client.svg?label=bintray)](https://bintray.com/joffrey-bion/maven/krossbow-client/_latestVersion)
[![Travis Build](https://img.shields.io/travis/joffrey-bion/krossbow/master.svg)](https://travis-ci.org/joffrey-bion/krossbow)
[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/joffrey-bion/krossbow/blob/master/LICENSE)

A coroutine-based Kotlin multiplatform WebSocket client and [STOMP 1.2](https://stomp.github.io/index.html) client
 over web sockets.

***This project is experimental, it's still in its early development stage.***

## Usage

This is how to create a client and interact with it (the verbose way):

```kotlin
val client = StompClient() // custom WebSocketClient and other config can be passed in here
val session = client.connect(url) // optional login/passcode can be provided here

try {
    session.send("/some/destination", MyPojo("Custom", 42)) 

    val subscription = session.subscribe<MyMessage>("/some/topic/destination")
    val firstMessage: MyMessage = subscription.messages.receive()

    println("Received: $firstMessage")
    subscription.unsubscribe()
} finally {
    session.disconnect()
}
```

If the STOMP session is only used in one place like this, we can get rid of the `try`/`catch` and `disconnect()` 
automatically by calling `useSession` (similar to `Closeable.use()`):

```kotlin
KrossbowClient().useSession(url) { // this: KrossbowSession

    send("/some/destination", CustomObject("Typed values", 42))

    val (messages) = subscribe<Message>("/some/topic/destination")

    val firstMessage = messages.receive()
    println("Received: $firstMessage")
}
```

### Message payload conversion

By default, Kotlinx Serialization is used (because cross-platform), but it can be customized.

For instance, on the JVM, you can use the `JacksonConverter` this way:

```kotlin
val client = StompClient {
    messageConverter = JacksonConverter()
}
```

You can use it with your own `ObjectMapper` this way:

```kotlin
val objectMapper = jacksonObjectMapper().enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
val client = StompClient {
    messageConverter = JacksonConverter(objectMapper)
}
```

You can also implement your own `MessageConverter` yourself, and pass it when configuring the client.

## Adding the dependency

All the dependencies are currently published to Bintray JCenter.
They are not yet available on npm yet.

### Common library

```kotlin
// common source set
implementation("org.hildan.krossbow:krossbow-stomp-metadata:$krossbowVersion")

// jvm source set
implementation("org.hildan.krossbow:krossbow-stomp-jvm:$krossbowVersion")

// js source set
implementation("org.hildan.krossbow:krossbow-stomp-js:$krossbowVersion")
```

## Project structure
 
This project contains the following modules:
- `krossbow-stomp`: the multiplatform STOMP client to use as a STOMP library in common, JVM or JS projects. It
 implements the STOMP 1.2 protocol on top of a websocket API defined by the `krossbow-websocket-api` module.
- `krossbow-websocket-api`: a common WebSocket API that the STOMP client relies on, to enable the use of custom
 WebSocket clients. This also provides a default JS client implementations using the Browser's native WebSocket.
- `krossbow-websocket-sockjs`: a multiplatform `WebSocketClient` implementation for use with SockJS servers. It uses
 Spring's SockJSClient on JVM, and npm `sockjs-client` for JavaScript (NodeJS and browser).
- `krossbow-websocket-spring`: a JVM implementation of the web socket API using Spring's WebSocketClient. Provides
 both a normal WebSocket client and a SockJS one.
