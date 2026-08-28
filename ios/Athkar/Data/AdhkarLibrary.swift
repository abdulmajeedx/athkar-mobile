import Foundation

/// One chapter of the bundled collection.
struct Chapter: Identifiable, Hashable {
    let id: String
    let title: String
    let items: [Dhikr]
}

/// One reading, with the number of times it is repeated.
struct Dhikr: Identifiable, Hashable {
    let id: String
    let chapterID: String
    let text: String
    let repeatCount: Int
}

/// The bundled corpus: the complete text of *Hisn al-Muslim*, read once from the app bundle.
///
/// There is no database. The collection is read-only and a few hundred entries, so parsing it at
/// launch costs less than the machinery of a store would — and the only mutable state, favourites
/// and the current tally, is a set of ids that belongs in `UserDefaults` anyway.
final class AdhkarLibrary {

    static let shared = AdhkarLibrary()

    let chapters: [Chapter]
    let source: Source

    struct Source: Decodable {
        let name: String
        let url: String
        let fetchedAt: String
    }

    private let byID: [String: Dhikr]

    private init() {
        guard let decoded = Self.loadBundle() else {
            // A missing or malformed resource is a packaging error, not a runtime condition; an
            // empty library shows an explicit empty state rather than crashing on the user.
            chapters = []
            byID = [:]
            source = Source(name: "", url: "", fetchedAt: "")
            return
        }

        let titles = Dictionary(
            uniqueKeysWithValues: decoded.categories.map { ($0.key, $0) }
        )
        var grouped: [String: [Dhikr]] = [:]
        for item in decoded.items.sorted(by: { $0.order < $1.order }) {
            grouped[item.category, default: []].append(
                Dhikr(
                    id: item.id,
                    chapterID: item.category,
                    text: item.body,
                    repeatCount: max(item.count, 1)
                )
            )
        }

        chapters = decoded.categories
            .sorted { $0.order < $1.order }
            .compactMap { category in
                guard let items = grouped[category.key], !items.isEmpty else { return nil }
                return Chapter(id: category.key, title: titles[category.key]?.title ?? category.title, items: items)
            }
        byID = Dictionary(
            uniqueKeysWithValues: chapters.flatMap(\.items).map { ($0.id, $0) }
        )
        source = decoded.source
    }

    func dhikr(id: String) -> Dhikr? { byID[id] }

    /// Chapters whose title contains the query; the whole list when it is blank.
    func chapters(matching query: String) -> [Chapter] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return chapters }
        return chapters.filter { $0.title.contains(trimmed) }
    }

    // MARK: - Bundle decoding

    private struct SeedFile: Decodable {
        let version: Int
        let source: Source
        let categories: [SeedCategory]
        let items: [SeedItem]
    }

    private struct SeedCategory: Decodable {
        let key: String
        let title: String
        let order: Int
        let count: Int
    }

    private struct SeedItem: Decodable {
        let id: String
        let category: String
        let body: String
        let count: Int
        let order: Int
    }

    private static func loadBundle() -> SeedFile? {
        guard let url = Bundle.main.url(forResource: "athkar_seed", withExtension: "json"),
              let data = try? Data(contentsOf: url)
        else { return nil }
        return try? JSONDecoder().decode(SeedFile.self, from: data)
    }
}
