import MediCabinetCore

/// Everything the UI layer needs, built once at launch. Views see repository protocols only.
struct AppEnvironment: Sendable {
    let locations: any LocationRepository
    let boxes: any BoxRepository
    let clock: any Clock
    /// The bundled BDPM reference data; opened lazily, never during launch.
    let reference: any ReferenceDataSource
}
