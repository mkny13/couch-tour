import SwiftUI
import CouchTourKit

struct CompareSourcesView: View {
    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("Compare Sources")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(colors.textPrimary)
                .padding(.top, 16)

            Text("Fast-switching preview at the current track position.")
                .font(.system(size: 13))
                .foregroundStyle(colors.textSecondary)

            if !player.isComparingSources {
                ProgressView("Loading preview players...")
                    .frame(minHeight: 200)
            } else {
                List(player.comparisonSources, id: \.id) { source in
                    let isActive = player.activeComparisonSourceId == source.id
                    Button {
                        player.switchComparisonSource(to: source.id)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: isActive ? "speaker.wave.3.fill" : "speaker.fill")
                                .font(.system(size: 14))
                                .foregroundStyle(isActive ? colors.accent : colors.textMuted)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(source.label)
                                    .font(.system(size: 14, weight: isActive ? .medium : .regular))
                                    .foregroundStyle(isActive ? colors.accent : colors.textPrimary)
                                
                                HStack(spacing: 6) {
                                    if source.hasFlac {
                                        sourceBadge("FLAC", color: .green)
                                    } else {
                                        sourceBadge("MP3", color: .secondary)
                                    }
                                    if source.isSoundboard {
                                        sourceBadge("SBD", color: .accentColor)
                                    }
                                    if source.looksLikeMatrix {
                                        sourceBadge("Matrix?", color: .purple)
                                    }
                                }
                            }
                            Spacer()
                        }
                        .padding(.vertical, 8)
                        .padding(.horizontal, 12)
                        .background(isActive ? colors.accent.opacity(0.15) : Color.clear)
                        .cornerRadius(8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
                .frame(minHeight: 200, maxHeight: 400)
            }

            HStack(spacing: 16) {
                Button("Cancel") {
                    player.exitCompareSourcesMode(confirmSelection: false)
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Button("Confirm Selection") {
                    player.exitCompareSourcesMode(confirmSelection: true)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!player.isComparingSources)
            }
            .padding(.bottom, 16)
        }
        .frame(width: 400)
        .background(colors.background)
        .onDisappear {
            // Safety cleanup if dismissed by other means (e.g., clicking away from a popover without clicking a button)
            if player.isComparingSources {
                player.exitCompareSourcesMode(confirmSelection: false)
            }
        }
    }

    private func sourceBadge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.caption2)
            .fontWeight(.bold)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(color)
    }
}
