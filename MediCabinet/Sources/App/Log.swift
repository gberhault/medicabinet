import OSLog

/// Loggers. Logs are for operations, not for a copy of the cabinet: never put box, lot, serial
/// or product values in a message. Interpolated values stay at the default (private) level;
/// opting a value into public logging is forbidden by lint.
enum Log {
    static let subsystem = "com.medicabinet.app"

    static let bootstrap = Logger(subsystem: subsystem, category: "bootstrap")
    static let persistence = Logger(subsystem: subsystem, category: "persistence")
}
