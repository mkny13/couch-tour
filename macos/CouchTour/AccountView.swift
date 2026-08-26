import CouchTourKit
import SwiftUI

/// phish.in account login (#57, port of Android's LoginScreen). Unlocks phish.in's
/// server-side likes and playlists for Phish specifically — Relisten has no account system
/// at all, so this screen only ever concerns the phish.in backend.
struct AccountView: View {
    @ObservedObject var session: PhishInSession

    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Form {
            if let username = session.username {
                Section {
                    Text("Signed in as \(username)")
                    Button("Log out") { session.logout() }
                }
            } else {
                Section {
                    Text(
                        "Signing in unlocks likes and playlists on phish.in. Your password is " +
                            "sent once to log in and is never stored — only the resulting session token is."
                    )
                    .foregroundStyle(.secondary)
                }
                Section {
                    TextField("Email", text: $email)
                        .textContentType(.username)
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                        .onSubmit { logIn() }
                    if let error {
                        Text(error).foregroundStyle(.red)
                    }
                    Button("Log in") { logIn() }
                        .disabled(busy || email.isEmpty || password.isEmpty)
                }
            }
            Section {
                Text(Bundle.main.appVersionString)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .formStyle(.grouped)
    }

    private func logIn() {
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !busy, !trimmedEmail.isEmpty, !password.isEmpty else { return }
        busy = true
        error = nil
        Task {
            do {
                try await session.login(email: trimmedEmail, password: password)
                password = ""
            } catch let apiError as APIException where apiError.unauthorized {
                error = "Email or password not recognized."
            } catch {
                self.error = "Couldn't log in: \(error.localizedDescription)"
            }
            busy = false
        }
    }
}
