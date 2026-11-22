package com.digistack.bank.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * NotificationService — P01 v13
 *
 * Sends transactional email notifications via the WAS-managed
 * JNDI Mail Session (mail/BankMailSession).
 *
 * Design decisions:
 * ─────────────────────────────────────────────────────────────
 * 1. All mail config (SMTP host, port, credentials, STARTTLS)
 *    lives entirely in WAS configuration — not in this class.
 *    This class only does: lookup Mail Session from JNDI → send.
 *    Changing the SMTP provider requires zero code changes.
 *
 * 2. Sender address is looked up via JNDI Name Space Binding
 *    (digistack/mail/senderAddress) configured in Sprint 2.
 *    Changing the sender requires only an Admin Console change —
 *    no recompile, no redeployment.
 *
 * 3. Recipient email is fetched from the users table via
 *    jdbc/BankDS (the same WAS-managed DataSource used by all
 *    other services). No second DataSource is introduced.
 *    The caller (WithdrawServlet) passes userId only —
 *    this service resolves the email address itself.
 *
 * 4. This class declares checked exceptions (MessagingException,
 *    SQLException, NamingException) so the caller is forced to
 *    handle them. WithdrawServlet catches them in a separate
 *    try-catch block — mail failure must NEVER roll back a Withdraw
 *    or prevent the success page from rendering.
 * ─────────────────────────────────────────────────────────────
 *
 * JNDI resources used:
 *   mail/BankMailSession         — WAS Mail Session (Sprint 1)
 *   digistack/mail/senderAddress — Name Space Binding (Sprint 2)
 *   jdbc/BankDS                  — DataSource (v7, unchanged)
 */
public class NotificationService {

    /**
     * Sends a withdrawal confirmation email to the account holder.
     *
     * Flow:
     *   1. Fetch recipient email + username from users table (jdbc/BankDS)
     *   2. Look up WAS Mail Session from JNDI (mail/BankMailSession)
     *   3. Look up sender address from JNDI (digistack/mail/senderAddress)
     *   4. Build the email message
     *   5. Send via Transport.send() — WAS authenticates to Gmail
     *      using the App Password stored in the Mail Session config
     *
     * @param userId     The logged-in user's ID (used to fetch email from DB)
     * @param amount     The withdrawal amount as a plain string (e.g. "500.00")
     * @param newBalance The balance after withdrawal (formatted, e.g. "4500.00")
     * @throws MessagingException if the email cannot be sent
     * @throws SQLException       if the user's email cannot be fetched from DB
     * @throws NamingException    if a JNDI lookup fails
     */
    public void sendWithdrawEmail(int userId,
                                  String amount,
                                  String newBalance)
            throws MessagingException, SQLException, NamingException {

        // ── Step 1: Fetch recipient details from DB ──────────────────────
        // We look up the user's email and display name from the users table.
        // The session does not carry the email address, so we query the DB.
        String[] userDetails = fetchUserDetails(userId);
        String recipientEmail = userDetails[0];
        String username       = userDetails[1];

        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            throw new MessagingException(
                "No email address on record for userId=" + userId +
                " — cannot send withdrawal notification.");
        }

        // ── Step 2: Look up the WAS-managed Mail Session via JNDI ────────
        // This Session object was configured in Sprint 1.
        // It already contains: smtp.gmail.com, port 587, STARTTLS=true,
        // auth=true, and the App Password stored encrypted in WAS config.
        // We do not set any of those properties here — WAS handles it.
        InitialContext ctx   = new InitialContext();
        Session mailSession  = (Session) ctx.lookup("mail/BankMailSession");

        // ── Step 3: Look up the sender address via JNDI ──────────────────
        // This is the Name Space Binding configured in Sprint 2.
        // Returns "venkateshsap413@gmail.com" — the value set in WAS.
        String sender = (String) ctx.lookup("digistack/mail/senderAddress");

        // ── Step 4: Build the email message ──────────────────────────────
        // MimeMessage is constructed with our JNDI Session so that
        // Transport.send() uses that Session's SMTP properties.
        Message message = new MimeMessage(mailSession);
        message.setFrom(new InternetAddress(sender));
        message.setRecipients(
            Message.RecipientType.TO,
            InternetAddress.parse(recipientEmail));
        message.setSubject("Withdrawal Confirmation \u2014 DigiStack Bank");
        message.setText(buildEmailBody(username, amount, newBalance));

        // ── Step 5: Send ──────────────────────────────────────────────────
        // Transport.send() uses the properties embedded in the MimeMessage's
        // Session. WAS authenticates to smtp.gmail.com:587 using the STARTTLS
        // handshake and the App Password stored in the Mail Session config.
        // No credential appears in this code.
        Transport.send(message);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds the plain-text body for a withdrawal confirmation email.
     *
     * Plain text is used deliberately — no HTML, no images, no external
     * dependencies. Every mail client renders plain text correctly.
     *
     * @param username   The user's login name (shown in the greeting)
     * @param amount     The withdrawal amount
     * @param newBalance The remaining balance after withdrawal
     * @return           The formatted email body as a plain-text String
     */
    private String buildEmailBody(String username,
                                  String amount,
                                  String newBalance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(username).append(",\n\n");
        sb.append("This is a confirmation that a withdrawal has been ")
          .append("processed on your DigiStack Bank account.\n\n");
        sb.append("Transaction Details\n");
        sb.append("-------------------------------------------\n");
        sb.append("Transaction Type : Withdrawal\n");
        sb.append("Amount           : Rs. ").append(amount).append("\n");
        sb.append("Remaining Balance: Rs. ").append(newBalance).append("\n");
        sb.append("-------------------------------------------\n\n");
        sb.append("If you did not authorise this withdrawal, ")
          .append("please contact DigiStack Bank support immediately.\n\n");
        sb.append("Regards,\n");
        sb.append("DigiStack Bank\n\n");
        sb.append("----------------------------------------------\n");
        sb.append("This is an automated message. ");
        sb.append("Please do not reply to this email.\n");
        sb.append("DigiStack Bank — For educational purposes only.\n");
        return sb.toString();
    }

    /**
     * Fetches the user's email address and username from the users table.
     *
     * Uses jdbc/BankDS — the same WAS-managed DataSource used by all
     * other services. The connection is obtained and closed within this
     * method (try-with-resources). No connection is left open.
     *
     * Why fetch from DB rather than pass email as a parameter?
     * WithdrawServlet's session does not carry the email address.
     * Rather than change the session structure (a cross-cutting concern),
     * we fetch it here — this service already has access to jdbc/BankDS
     * and knows the userId. The DB query is a single indexed SELECT
     * on the primary key — negligible overhead.
     *
     * @param userId The user's primary key in the users table
     * @return       String[2]: index 0 = email, index 1 = username
     *               Either value may be null if not found in DB.
     * @throws SQLException    if the query fails
     * @throws NamingException if jdbc/BankDS cannot be resolved from JNDI
     */
    private String[] fetchUserDetails(int userId)
            throws SQLException, NamingException {

        InitialContext ctx = new InitialContext();
        DataSource ds = (DataSource) ctx.lookup("jdbc/BankDS");

        String email    = null;
        String username = null;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT email, username FROM users WHERE id = ?")) {

            ps.setInt(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    email    = rs.getString("email");
                    username = rs.getString("username");
                }
            }
        }

        return new String[]{ email, username };
    }
}