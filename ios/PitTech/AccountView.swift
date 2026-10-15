import SwiftUI

struct NativeAccountView: View {
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var code = ""
    @State private var confirmingSignOut = false
    private enum Field: Hashable { case email, code }
    @FocusState private var focused: Field?
    var body: some View {
        Form {
            Section {
                Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.")
            }
            if !account.enabled {
                Section { Text("Accounts arrive with community recipes and private sync.").accessibilityIdentifier("account-unavailable") }
            } else if account.signedIn {
                Section("Account") {
                    Text("Signed in").accessibilityIdentifier("account-signed-in")
                    Button("Check connection") { Task { await account.checkSession() } }.disabled(account.busy)
                    Button("Sign out", role: .destructive) { confirmingSignOut = true }.disabled(account.busy)
                }
            } else {
                Section("Sign in by email") {
                    TextField("Email address", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focused, equals: .email).privacySensitive()
                        .accessibilityIdentifier("account-email")
                    Button("Send sign-in email") { focused = nil; Task { await account.start(email) } }
                        .disabled(account.busy || email.isEmpty).accessibilityIdentifier("account-start")
                }
                if let challengeEmail = account.challengeEmail {
                    Section("Check your email") {
                        Text("Enter the six-digit code sent to \(challengeEmail). You can open the email on another device.").privacySensitive()
                        TextField("Six-digit code", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode).focused($focused, equals: .code).privacySensitive()
                            .accessibilityIdentifier("account-code")
                        Button(account.interruptedVerification ? "Retry sign-in" : account.pendingLink != nil ? "Confirm email link" : "Verify code") {
                            focused = nil; Task { await account.verify(code); code = "" }
                        }.disabled(account.busy).accessibilityIdentifier("account-verify")
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            Button("Send a new code") { Task { await account.start(challengeEmail, resend: true) } }
                                .disabled(account.busy || !account.canResend).accessibilityIdentifier("account-resend")
                        }
                        Text("Codes expire after 15 minutes. Wait at least a minute before requesting another.").font(.footnote)
                    }
                }
            }
            if account.busy { ProgressView("Connecting…") }
            if let message = account.message { Section { Text(message).accessibilityIdentifier("account-message") } }
        }
        .navigationTitle("Account").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Hide keyboard") { focused = nil } }
        }
        .scrollDismissesKeyboard(.interactively)
        .confirmationDialog("Sign out on this device?", isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await account.signOut() } }
        } message: { Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.") }
    }
}
