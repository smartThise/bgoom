import AppKit
import Foundation
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private var launchedForClick = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        registerCategories(center: center)

        let args = parseArgs()
        if let title = args["title"], let body = args["body"] {
            let filePath = args["open"] ?? ""
            postNotification(center: center, title: title, body: body, filePath: filePath)
        } else {
            // Launched by macOS Notification Center when user clicked a delivered banner or action button.
            // Wait briefly for didReceive callback, then exit cleanly (zero idle RAM).
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                NSApp.terminate(nil)
            }
        }
    }

    private func registerCategories(center: UNUserNotificationCenter) {
        // Single primary action so macOS shows "打开文件夹" directly without nesting inside a submenu,
        // while clicking the notification body itself directly opens the file.
        let openFolderAction = UNNotificationAction(
            identifier: "OPEN_FOLDER",
            title: "打开文件夹",
            options: [.foreground]
        )
        let fileCategory = UNNotificationCategory(
            identifier: "BGOOM_FILE",
            actions: [openFolderAction],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([fileCategory])
    }

    private func postNotification(center: UNUserNotificationCenter, title: String, body: String, filePath: String) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else {
                // Fallback to osascript if notification permission hasn't been granted yet
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
                // Keep process alive briefly so delivery settles, then exit to maintain zero idle RAM;
                // macOS automatically relaunches BgoomNotify.app when the user clicks the notification later.
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
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
            let fm = FileManager.default
            if response.actionIdentifier == "OPEN_FOLDER" || response.actionIdentifier == "REVEAL_FINDER" {
                if fm.fileExists(atPath: expanded) {
                    // Open containing folder in Finder and highlight the received file
                    NSWorkspace.shared.activateFileViewerSelecting([url])
                } else {
                    let parent = url.deletingLastPathComponent()
                    if fm.fileExists(atPath: parent.path) {
                        NSWorkspace.shared.open(parent)
                    }
                }
            } else {
                // Default tap on notification body -> open the file directly
                if fm.fileExists(atPath: expanded) {
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
