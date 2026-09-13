import SwiftUI

/// The tasbih.
///
/// Its own surface rather than the app's page: this is the one screen counted on without being
/// read, and a deep ground with a single lit ring is both easier on the eye and unmistakably a
/// different tool from the pages behind it. The ground is the hour's sky, like every other surface
/// in the app, so it belongs to the same evening as the prayer screen without borrowing its layout.
///
/// The whole area above the controls counts. A tasbih is used without looking — the thumb should
/// find it anywhere, not hunt for a button.
struct TasbihView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @Environment(\.skyPhase) private var sky

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                counter
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: count)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel("عُدّ")

                controls
            }
            .background(sky.gradient.ignoresSafeArea())
            .navigationTitle("المسبحة")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
        }
    }

    private var state: TasbihState { preferences.tasbih }

    private var counter: some View {
        VStack(spacing: 20) {
            Text(state.dhikr.arabic)
                .font(.title2.weight(.semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.14), lineWidth: 18)

                Circle()
                    .trim(from: 0, to: max(0, min(1, state.progress)))
                    .stroke(
                        sky.accent,
                        style: StrokeStyle(lineWidth: 18, lineCap: .round)
                    )
                    // Trim starts at three o'clock; the ring has to start at the top.
                    .rotationEffect(.degrees(-90))
                    // Eased rather than snapped: the ring is the only thing on screen that moves,
                    // and a step change reads as a glitch where a sweep reads as progress.
                    .animation(.easeOut(duration: 0.22), value: state.count)

                VStack(spacing: 2) {
                    Text("\(state.count)")
                        .font(.system(size: 56, weight: .bold))
                        .foregroundStyle(.white)
                        .monospacedDigit()
                    Text("من \(state.target)")
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.7))
                        .monospacedDigit()
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(state.count) من \(state.target)")
            }
            .frame(maxWidth: 260)
            .aspectRatio(1, contentMode: .fit)
            .padding(.horizontal, 40)

            Text(state.rounds > 0 ? "أتممت \(roundsLabel(state.rounds))" : "اضغط في أي مكان للعدّ")
                .font(.callout)
                .foregroundStyle(state.rounds > 0 ? sky.accent : Color.white.opacity(0.6))
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            // Scrolled rather than wrapped: seven phrases and five targets would otherwise push the
            // ring off a small screen, and the ring is the screen.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(TasbihPhrase.allCases) { phrase in
                        chip(phrase.arabic, selected: phrase == state.dhikr) {
                            preferences.setTasbih(state.selecting(phrase))
                        }
                    }
                }
                .padding(.horizontal, 16)
            }

            HStack(spacing: 8) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(TasbihState.targets, id: \.self) { target in
                            chip("\(target)", selected: target == state.target) {
                                preferences.setTasbih(state.retargeted(target))
                            }
                        }
                    }
                    .padding(.leading, 16)
                }

                Button {
                    preferences.setTasbih(state.reset())
                } label: {
                    Image(systemName: "arrow.counterclockwise")
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Color.white.opacity(0.12), in: Circle())
                }
                .accessibilityLabel("تصفير العدّ")
                .padding(.trailing, 16)
            }
        }
        .padding(.vertical, 12)
        .background(Color.black.opacity(0.18))
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.callout)
                .foregroundStyle(selected ? Color.black.opacity(0.8) : Color.white.opacity(0.85))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule().fill(selected ? sky.accent : Color.white.opacity(0.08))
                )
                .overlay(
                    Capsule().stroke(Color.white.opacity(selected ? 0 : 0.25), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private func count() {
        let next = state.incremented()
        // A different, heavier pulse when a round closes, so the hand knows without the eyes.
        UIImpactFeedbackGenerator(style: next.rounds > state.rounds ? .heavy : .light)
            .impactOccurred()
        preferences.setTasbih(next)
    }

    /// Arabic counts its rounds in three forms; "أتممت 2 دورة" is not one of them.
    private func roundsLabel(_ rounds: Int) -> String {
        switch rounds {
        case 1: return "دورة"
        case 2: return "دورتين"
        case 3...10: return "\(rounds) دورات"
        default: return "\(rounds) دورة"
        }
    }
}
