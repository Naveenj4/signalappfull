# PIT Messenger Architecture

## Components

- **Android client** (`signal-android/`): Android messaging application using Signal Protocol libraries, local encrypted storage, and the registration UI.
- **Signal Java server** (`signal-server/`): Dropwizard service providing registration, account, and messaging APIs.
- **MySQL**: Persistent server storage configured through local environment variables.
- **Redis**: Required for transient cache, pub/sub, and related runtime state; not a replacement for persistent SQL storage.
- **External integrations**: SMS delivery and media/calling infrastructure require their own valid provider configuration. Sample values in `signal-server/config/local.yml` are not production credentials or services.
- **Optional Node.js services** (`custom-backend/`, `signal-android/server/`): Separate test/custom backends. They are not the official Java server and are not started by `start_all_servers.bat`.

## Registration flow

The Android client talks to the Signal Java server on port `8080`. The Java server validates registration requests and stores account data through its configured persistence layer. The custom Node.js OTP backend on port `8088` is a separate flow and must not be mistaken for the Java server's registration API.

## Data boundaries

Messages remain end-to-end encrypted by the client protocol. MySQL stores server-side account and service records; Redis supports transient runtime functions. Media storage, calling, push delivery, and other external integrations must be configured independently before those features can be considered operational.

## Local configuration

Keep database passwords, API keys, certificates, and other credentials in ignored local configuration files or environment variables. Do not commit populated secret files.
