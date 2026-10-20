import OSLog

/// Loggers. Logs are for operations, not for a copy of the cabinet: never put box, lot, serial
/// or product values in a message. Interpolated values stay at the default (private) level;
/// opting a value into public logging is forbidden by lint.
enum Log {
    static let subsystem = "com.medicabinet.app"

    static let bootstrap = Logger(subsystem: subsystem, category: "bootstrap")
    static let persistence = Logger(subsystem: subsystem, category: "persistence")
    static let reference = Logger(subsystem: subsystem, category: "reference")
    static let reminders = Logger(subsystem: subsystem, category: "reminders")
    /// Export, import and backups: the stage and the error kind only, never file contents.
    static let archive = Logger(subsystem: subsystem, category: "archive")

    /// A product name that could not be remembered after its box was saved (the box is kept): the error kind only.
    @Sendable static func memoryFailed(_ error: any Error) {
        persistence.error("Remembering a product name failed: \(String(describing: type(of: error)))")
    }
}
