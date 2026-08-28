import SwiftUI

@main
struct AthkarApp: App {

    @StateObject private var preferences = PreferencesStore()
    @StateObject private var location = LocationProvider()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(preferences)
                .environmentObject(location)
                // The UI is Arabic on every screen, so the direction is pinned rather than
                // inherited: a phone set to English would otherwise mirror the layout away from
                // the text it is laying out.
                .environment(\.layoutDirection, .rightToLeft)
        }
        .onChange(of: scenePhase) { phase in
            // iOS gives an app no reliable background moment, so the rolling notification window is
            // rebuilt whenever the app is in front of the user.
            guard phase == .active else { return }
            Task { await PrayerNotificationScheduler.reschedule(with: preferences) }
        }
    }
}

struct RootView: View {
    var body: some View {
        TabView {
            AthkarView()
                .tabItem { Label("الأذكار", systemImage: "list.bullet") }

            PrayerTimesView()
                .tabItem { Label("الصلاة", systemImage: "clock") }

            QiblaView()
                .tabItem { Label("القبلة", systemImage: "location.north.line") }
        }
    }
}
