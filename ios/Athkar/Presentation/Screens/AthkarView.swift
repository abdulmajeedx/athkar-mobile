import SwiftUI

/// The bundled collection, two levels deep: the chapter index, then the readings of one chapter.
///
/// A flat list is the obvious alternative and the wrong one — the collection runs to hundreds of
/// readings across 132 chapters, and finding "أذكار النوم" by scrolling past all of them is not
/// finding it. Tapping a reading advances its tally and wraps back to zero at the target.
struct AthkarView: View {

    @EnvironmentObject private var preferences: PreferencesStore
    @Environment(\.colorScheme) private var scheme
    @State private var query = ""

    private let library = AdhkarLibrary.shared

    var body: some View {
        NavigationStack {
            List {
                if !preferences.favourites.isEmpty, query.isEmpty {
                    NavigationLink {
                        ChapterView(title: "المفضلة", items: favouriteItems)
                    } label: {
                        ChapterRow(
                            title: "المفضلة",
                            count: favouriteItems.count,
                            highlighted: true
                        )
                    }
                }

                ForEach(library.chapters(matching: query)) { chapter in
                    NavigationLink {
                        ChapterView(title: chapter.title, items: chapter.items)
                    } label: {
                        ChapterRow(title: chapter.title, count: chapter.items.count, highlighted: false)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $query, prompt: "ابحث في الأبواب")
            .navigationTitle("الأذكار")
            .overlay {
                if library.chapters.isEmpty {
                    ContentUnavailableViewCompat(
                        title: "تعذّر تحميل الأذكار",
                        message: "ملف المحتوى غير موجود في حزمة التطبيق."
                    )
                }
            }
        }
    }

    private var favouriteItems: [Dhikr] {
        library.chapters
            .flatMap(\.items)
            .filter { preferences.isFavourite($0.id) }
    }
}

private struct ChapterRow: View {
    let title: String
    let count: Int
    let highlighted: Bool

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack {
            if highlighted {
                Image(systemName: "star.fill")
                    .foregroundStyle(Theme.gold(scheme))
            }
            Text(title)
            Spacer()
            Text("\(count)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

/// One chapter's readings, each with its own tally.
struct ChapterView: View {

    let title: String
    let items: [Dhikr]

    @EnvironmentObject private var preferences: PreferencesStore
    @Environment(\.colorScheme) private var scheme
    /// Tallies belong to *this sitting*, not to the dhikr: persisting them would mean yesterday's
    /// half-finished tasbih greeting the user this morning.
    @State private var counts: [String: Int] = [:]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(items) { item in
                    DhikrCard(
                        item: item,
                        count: counts[item.id] ?? 0,
                        isFavourite: preferences.isFavourite(item.id),
                        onTap: { advance(item) },
                        onToggleFavourite: { preferences.toggleFavourite(item.id) }
                    )
                }
            }
            .padding(16)
        }
        .background(Theme.background(scheme))
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if items.isEmpty {
                ContentUnavailableViewCompat(
                    title: "لا يوجد شيء هنا",
                    message: "لم تضف أي ذكر إلى المفضلة بعد."
                )
            }
        }
    }

    private func advance(_ item: Dhikr) {
        let current = counts[item.id] ?? 0
        counts[item.id] = current >= item.repeatCount ? 0 : current + 1
    }
}

private struct DhikrCard: View {

    let item: Dhikr
    let count: Int
    let isFavourite: Bool
    let onTap: () -> Void
    let onToggleFavourite: () -> Void

    @Environment(\.colorScheme) private var scheme

    private var isComplete: Bool { count >= item.repeatCount }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(item.text)
                .arabicBody()
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 12) {
                Text(item.repeatCount > 1 ? "\(count) / \(item.repeatCount)" : "\(count)")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(isComplete ? Theme.gold(scheme) : Color.secondary)

                ProgressView(
                    value: Double(min(count, item.repeatCount)),
                    total: Double(max(item.repeatCount, 1))
                )
                .tint(isComplete ? Theme.gold(scheme) : Theme.primary(scheme))

                Button(action: onToggleFavourite) {
                    Image(systemName: isFavourite ? "star.fill" : "star")
                        .foregroundStyle(isFavourite ? Theme.gold(scheme) : Color.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isFavourite ? "إزالة من المفضلة" : "إضافة إلى المفضلة")
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 18)
                .fill(isComplete ? Theme.primary(scheme).opacity(0.15) : Theme.surface(scheme))
        )
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

/// `ContentUnavailableView` is iOS 17+; this keeps the deployment target at 16.
struct ContentUnavailableViewCompat: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
    }
}
