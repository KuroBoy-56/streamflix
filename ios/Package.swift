// swift-tools-version:5.9
// Lógica compartida de la app (sin interfaz): se compila y se prueba también en Linux.
// La app de iPhone (carpeta App) se arma con XcodeGen: ver project.yml.
import PackageDescription

let package = Package(
    name: "VODCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "VODCore", targets: ["VODCore"])],
    targets: [
        .target(name: "VODCore", path: "Core"),
        .testTarget(name: "CoreTests", dependencies: ["VODCore"], path: "Tests/CoreTests"),
    ]
)
