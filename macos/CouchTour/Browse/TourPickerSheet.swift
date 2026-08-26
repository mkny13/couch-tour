import CouchTourKit
import SwiftUI

/// Sheet allowing users to pick a past tour or specific year for defunct / non-touring artists (#68, D190).
/// Persists preference into GRDB `artist_tour_preferences` via `ProgressStore`.
struct TourPickerSheet: View {
    let artist: ArtistRef

    @EnvironmentObject private var appModel: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var periods: [PeriodRef] = []
    @State private var selectedPeriod: PeriodRef?
    @State private var availableTours: [String] = []
    @State private var selectedTour: String?
    @State private var currentPreference: ArtistTourPreference?

    @State private var isLoadingPeriods = true
    @State private var isLoadingTours = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                if let error = errorMessage {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
                }

                if let currentPreference {
                    Section("Current Next Stop Setting") {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                if let tour = currentPreference.tourName, !tour.isEmpty {
                                    Text("Tour: \(tour)")
                                        .fontWeight(.medium)
                                }
                                if let year = currentPreference.year, !year.isEmpty {
                                    Text("Year: \(year)")
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button("Clear / Default", role: .destructive) {
                                clearPreference()
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }

                Section("Select Year / Era") {
                    if isLoadingPeriods {
                        HStack {
                            ProgressView()
                                .controlSize(.small)
                            Text("Loading years...")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else if periods.isEmpty {
                        Text("No years found for \(artist.name)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("Year", selection: Binding(
                            get: { selectedPeriod },
                            set: { newPeriod in
                                selectedPeriod = newPeriod
                                selectedTour = nil
                                if let newPeriod {
                                    Task { await loadTours(for: newPeriod) }
                                } else {
                                    availableTours = []
                                }
                            }
                        )) {
                            Text("Select a year...").tag(PeriodRef?.none)
                            ForEach(periods, id: \.self) { period in
                                Text(period.label).tag(PeriodRef?.some(period))
                            }
                        }
                        .pickerStyle(.menu)
                    }
                }

                if let period = selectedPeriod {
                    Section("Select Tour in \(period.label) (Optional)") {
                        if isLoadingTours {
                            HStack {
                                ProgressView()
                                    .controlSize(.small)
                                Text("Loading tours...")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            Picker("Tour", selection: $selectedTour) {
                                Text("All Shows in \(period.label)").tag(String?.none)
                                ForEach(availableTours, id: \.self) { tour in
                                    Text(tour).tag(String?.some(tour))
                                }
                            }
                            .pickerStyle(.menu)

                            if availableTours.isEmpty {
                                Text("No distinct named tours found in \(period.label). All shows from this year will be tracked.")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section {
                    Text("Selecting a past tour or year tells the Next Couch Tour Stop shelf which shows to track and resolve next.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Track Tour for \(artist.name)")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        savePreference()
                    }
                    .disabled(selectedPeriod == nil && currentPreference == nil)
                }
            }
            .task {
                await initialize()
            }
        }
        .frame(minWidth: 420, minHeight: 380)
    }

    private func initialize() async {
        isLoadingPeriods = true
        errorMessage = nil

        // Load existing preference
        if let store = appModel.progressStore {
            currentPreference = try? store.getTourPreference(artistKey: artist.key)
        }

        do {
            let loadedPeriods = try await sourceFor(artist.backend).periods(artist: artist)
            // Filter out non-year periods like 'popular'
            periods = loadedPeriods.filter { $0.id != "popular" }

            // Preselect period if preference exists
            if let pref = currentPreference {
                if let year = pref.year, !year.isEmpty {
                    if let match = periods.first(where: { $0.label == year || $0.id == year }) {
                        selectedPeriod = match
                        selectedTour = pref.tourName
                        await loadTours(for: match)
                    }
                }
            }
        } catch {
            errorMessage = "Couldn't load years: \(error.localizedDescription)"
        }
        isLoadingPeriods = false
    }

    private func loadTours(for period: PeriodRef) async {
        isLoadingTours = true
        do {
            let shows = try await sourceFor(artist.backend).shows(artist: artist, period: period)
            let distinctTours = Set(
                shows.compactMap { $0.tourName?.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty && $0 != notPartOfATour }
            ).sorted()
            availableTours = distinctTours
        } catch {
            availableTours = []
        }
        isLoadingTours = false
    }

    private func savePreference() {
        guard let store = appModel.progressStore else {
            errorMessage = "Database is unavailable."
            return
        }

        guard let period = selectedPeriod else {
            dismiss()
            return
        }

        let preference = ArtistTourPreference(
            artistKey: artist.key,
            tourName: selectedTour,
            year: period.label
        )

        do {
            try store.saveTourPreference(preference)
            NextStop.resetCache()
            dismiss()
        } catch {
            errorMessage = "Couldn't save preference: \(error.localizedDescription)"
        }
    }

    private func clearPreference() {
        guard let store = appModel.progressStore else { return }
        do {
            try store.deleteTourPreference(artistKey: artist.key)
            currentPreference = nil
            selectedPeriod = nil
            selectedTour = nil
            availableTours = []
            NextStop.resetCache()
            dismiss()
        } catch {
            errorMessage = "Couldn't clear preference: \(error.localizedDescription)"
        }
    }
}
