import AppKit
import Foundation
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private var launchedForClick = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        let args = parseArgs()
        if let title = args["title"], let body = args["body"] {
            let filePath = args["open"] ?? ""
            postNotification(center: center, title: title, body: body, filePath: filePath)
        } else {
            // Launched by macOS Notification Center when user clicked a delivered banner.
            // Wait briefly for didReceive callback, then exit cleanly.
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                NSApp.terminate(nil)
            }
        }
    }

    private func postNotification(center: UNUserNotificationCenter, title: String, body: String, filePath: String) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else {
                // Fallback to osascript if user hasn't granted UNUserNotificationCenter yet
                let safeT = title.replacingOccurrences(of: "\"", with: "'")
                let safeB = body.replacingOccurrences(of: "\"", with: "'")
                let proc = Process()
                proc.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
                proc.arguments = ["-e", "display notification \"\(safeB)\" with title \"\(safeT)\" sound name \"Glass\""]
                try? proc.run()
                proc.waitUntilExit()
                DispatchQueue.main.async { NSApp.terminate(nil) }
                return
            }

            let openAction = UNNotificationAction(
                identifier: "OPEN_FILE",
                title: "直接打开",
                options: [.foreground]
            )
            let revealAction = UNNotificationAction(
                identifier: "REVEAL_FINDER",
                title: "在访达中显示",
                options: [.foreground]
            )
            let category = UNNotificationCategory(
                identifier: "BGOOM_FILE",
                actions: filePath.isEmpty ? [] : [openAction, revealAction],
                intentIdentifiers: [],
                options: []
            )
            center.setNotificationCategories([category])

            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            if !filePath.isEmpty {
                content.categoryIdentifier = "BGOOM_FILE"
                content.userInfo = ["filePath": filePath]
            }

            let request = UNNotificationRequest(
                identifier: "bgoom-\(UUID().uuidString)",
                content: content,
                trigger: nil
            )
            center.add(request) { _ in
                // Keep process alive briefly so delivery settles, then exit to keep zero idle RAM;
                // macOS automatically relaunches BgoomNotify.app if user clicks the banner later.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                    if !self.launchedForClick {
                        NSApp.terminate(nil)
                    }
                }
            }
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        launchedForClick = true
        if let path = response.notification.request.content.userInfo["filePath"] as? String, !path.isEmpty {
            let expanded = (path as NSString).expandingTildeInPath
            let url = URL(fileURLWithPath: expanded)
            if FileManager.default.fileExists(atPath: expanded) {
                if response.actionIdentifier == "REVEAL_FINDER" {
                    NSWorkspace.shared.activateFileViewerSelecting([url])
                } else {
                    NSWorkspace.shared.open(url)
                }
            }
        }
        completionHandler()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            NSApp.terminate(nil)
        }
    }

    private func parseArgs() -> [String: String] {
        var result: [String: String] = [:]
        let argv = CommandLine.arguments
        var i = 1
        while i < argv.count {
            let key = argv[i]
            if key.hasPrefix("--"), i + 1 < argv.count {
                result[String(key.dropFirst(2))] = argv[i + 1]
                i += 2
            } else {
                i += 1
            }
        }
        return result
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
