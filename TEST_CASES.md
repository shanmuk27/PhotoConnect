# PhotoConnect Regression Test Cases

Run these after deploying the PHP API and installing a fresh Android debug build.

## Android Smoke

1. Run `cd android_project && .\gradlew.bat testDebugUnitTest`.
   Expected: Gradle build succeeds. Current project has no unit test source files, so `testDebugUnitTest` reports `NO-SOURCE`.

## Automated Backend Trust Tests

1. Run `php tests/trust_flow_test.php`.
   Expected: all trust-flow checks pass.
   Covers: public URL normalization, Instagram/YouTube-only social verification URLs, GSTIN checksum, taker Stage 0/1/2/3 ladder, the rule that bookings do not bypass taker document verification, studio business verification, and studio trusted conditions.

2. Admin review endpoints require `ADMIN_API_KEY`.
   - Set `ADMIN_API_KEY` in the API environment.
   - Call `getVerificationQueue.php`, `reviewVerification.php`, or `downloadVerificationDocument.php` without `X-Admin-Token`.
   - Expected: `401 Admin authorization required`.
   - Repeat with `X-Admin-Token: <ADMIN_API_KEY>`.
   - Expected: request is accepted.

3. Web verification dashboard
   - Open `php_api/supportDashboard.php?view=verification`.
   - Expected: the page asks for the admin key and does not expose verification data before login.
   - Log in with `ADMIN_API_KEY`.
   - Expected: pending and not-submitted taker/studio requests appear as responsive cards, unverified badges are red, documents open only from the authenticated dashboard, and saving a review requires the confirmation modal.

## Reported Bug Regressions

1. Delete post instantly
   - Log in as a taker with at least two posts.
   - Open profile, open a post, delete it from the post viewer.
   - Expected: the post disappears immediately in the viewer and from the profile after returning.
   - Failure recovery check: turn off network before confirming delete.
   - Expected: the post is restored and the parent profile is not told that it was deleted.

2. Calendar half-day switch
   - Set one date to `first_half` busy.
   - Change the same date to `second_half` busy.
   - Expected: only `second_half` remains busy; the date must not become `full_day`.
   - Repeat from `second_half` to `first_half`.

3. Account settings social links
   - Register or edit a taker profile with main link, additional link, and additional link 2.
   - Open account settings and edit social accounts.
   - Expected: fields are generic link fields and save all three values, including the third link.

4. Explore/social link display
   - Open a taker that has a portfolio/social link.
   - Expected: the visible action uses the link icon/action, not the old camera icon.

5. Forgot password OTP auto verification
   - Tap forgot password, enter an existing email or phone, send reset code.
   - Enter all 6 OTP digits.
   - Expected: the OTP verifies automatically and the reset button becomes enabled after verification.

6. Forgot password nonexistent identity
   - Tap forgot password and enter an email or phone that is not registered.
   - Expected: API returns a clear account-not-found error and the app keeps the identity field editable.

7. Pincode area/city mapping
   - Register or edit address, enter a pincode with multiple post offices.
   - Pick one post office suggestion.
   - Expected: area is the selected post office/locality, while city/town uses the broader city, district, or taluk value.

8. Delete post pending state
   - Delete a post from profile or the post viewer.
   - Expected: the post stays visible but dimmed/disabled while the server request is pending.
   - Expected: the post disappears only after the server confirms deletion.
   - Failure recovery check: force the delete request to fail.
   - Expected: the post returns to normal and remains visible.

9. Forgot password verified OTP state
   - Send a reset code and enter the correct 6 digits.
   - Expected: the OTP field loses focus and becomes disabled after verification.

10. Account settings social link editor
   - Open account settings social accounts with no links.
   - Expected: only the main link input is visible.
   - Fill the main link.
   - Expected: the add-link button appears.
   - Add one or two extra links and save.
   - Expected: links persist and reopen as editable extra fields.

11. Explore social link icons
   - Use takers with Instagram, YouTube, and general website links.
   - Expected: explore cards show social/link icons and tapping each icon opens the correct URL.

12. Reviews empty state
   - Open a taker with zero reviews.
   - Expected: the reviews section consistently shows the no-reviews message.

13. Notifications sheet
   - Open the notifications tab/sheet with no notifications and with existing notifications.
   - Expected: loading stops and the sheet shows either notifications or the empty message.

## Backend/Data Regressions

1. Password reset uses unified users table
   - Reset password for a user registered after the unified users migration.
   - Expected: `users.password_hash` changes, refresh token fields are cleared, and login succeeds with the new password.

2. Actor/profile id resolution
   - Log in as a user with a taker profile and perform profile, post, availability, booking, event, and notification actions.
   - Expected: APIs use `takers.id` or `clients.id` where profile ids are required, not `users.id`.

3. Existing-user social/profile update:
   - Update a taker profile email and links from account settings.
   - Expected: email uniqueness is checked in `users`, `users.email` is updated, and linked profile data remains consistent.
