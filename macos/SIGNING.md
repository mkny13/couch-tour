# macOS signing

Releases and local installs are signed with a self-signed code-signing certificate, not
ad-hoc. Ad-hoc signing pins the app's designated requirement to its cdhash, which changes on
every build, so macOS Keychain forgets "Always Allow" on each update (#568). A stable
certificate keeps the requirement constant. Sparkle updates are unaffected (they verify the
EdDSA key, not this certificate).

## One-time setup

1. **Create the certificate.** Keychain Access → menu *Keychain Access* → *Certificate
   Assistant* → *Create a Certificate…*. Name: `Couch Tour Signing`. Identity Type: *Self
   Signed Root*. Certificate Type: *Code Signing*. Tick *Let me override defaults*, set
   validity to 3650 days, and keep clicking Continue; choose the *login* keychain.
2. **Export it.** In Keychain Access → *My Certificates*, right-click `Couch Tour Signing` →
   *Export…* → format `.p12`, choose a password. Save it somewhere temporary.
3. **Add GitHub secrets** (repo → Settings → Secrets and variables → Actions):
   ```bash
   base64 -i CouchTourSigning.p12 | gh secret set MACOS_SIGNING_CERT_P12 -R mkny13/couch-tour
   gh secret set MACOS_SIGNING_CERT_PASSWORD -R mkny13/couch-tour   # paste the .p12 password
   gh secret set MACOS_SIGNING_IDENTITY -R mkny13/couch-tour --body "Couch Tour Signing"
   ```
4. **Back up the `.p12` and delete the temporary copy.** Losing it means a new certificate,
   which means one more round of Keychain prompts.
5. Local installs (`macos/scripts/install.sh`) pick up `Couch Tour Signing` from your login
   keychain automatically.

The first update after switching prompts once per Keychain item (phish.in login, sync
token). Click *Always Allow*; later updates should not prompt.

The release workflow fails if the secrets are missing or if the built app's designated
requirement still contains a cdhash.
