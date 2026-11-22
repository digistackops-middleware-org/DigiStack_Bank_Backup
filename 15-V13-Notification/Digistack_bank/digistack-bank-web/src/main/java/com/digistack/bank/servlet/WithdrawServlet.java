package com.digistack.bank.servlet;

import java.io.IOException;
import java.sql.SQLException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.digistack.bank.exception.InsufficientFundsException;
import com.digistack.bank.model.Account;
import com.digistack.bank.service.AccountService;
import com.digistack.bank.service.NotificationService;
import com.digistack.bank.service.WithdrawService;

/**
 * WithdrawServlet — P01 v13
 *
 * v13 change: After a successful withdrawal, fires a confirmation
 * email via NotificationService. The notification call is wrapped
 * in its own try-catch — mail failure must NEVER roll back the
 * withdrawal or show the user an error page.
 *
 * v6 origin: Handles GET /Withdraw (display form) and
 * POST /Withdraw (process withdrawal, PRG redirect).
 *
 * GET flow:
 *   1. Session guard.
 *   2. Load account via AccountService.
 *   3. Read PRG parameters.
 *   4. Forward to Withdraw.jsp.
 *
 * POST flow:
 *   1. Session guard.
 *   2. Read amount from form.
 *   3. Call WithdrawService.withdraw().
 *   4. On success → send notification email (fire-and-forget).
 *   5. Redirect /Withdraw?result=success&amount=<n> regardless
 *      of whether the email succeeded or failed.
 *   6. On withdrawal failure → redirect /Withdraw?error=<message>.
 */
@WebServlet(name = "WithdrawServlet",
            urlPatterns = {"/Withdraw", "/withdraw"})
public class WithdrawServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final AccountService  accountService  = new AccountService();
    private final WithdrawService withdrawService = new WithdrawService();

    /**
     * GET /Withdraw — load and display the withdrawal form.
     * Unchanged from v6.
     */
    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws ServletException, IOException {

        // ── Session Guard ──
        HttpSession session = request.getSession(false);
        if (session == null ||
                session.getAttribute("userId") == null) {
            response.sendRedirect(
                request.getContextPath() + "/Login");
            return;
        }

        int userId = (Integer) session.getAttribute("userId");

        // ── Load Account ──
        Account account  = null;
        String loadError = null;

        try {
            account = accountService.getAccountByUserId(userId);
            if (account == null) {
                loadError = "No account found. Please contact support.";
            }
        } catch (SQLException e) {
            log("WithdrawServlet GET: DB error for userId=" +
                userId + " — " + e.getMessage(), e);
            loadError = "Unable to load account. Please try again.";
        }

        // ── Read PRG parameters ──
        String result    = request.getParameter("result");
        String amountStr = request.getParameter("amount");
        String error     = request.getParameter("error");

        // ── Set Attributes for Withdraw.jsp ──
        request.setAttribute("account",   account);
        request.setAttribute("loadError", loadError);
        request.setAttribute("result",    result);
        request.setAttribute("amount",    amountStr);
        request.setAttribute("error",     error);

        request.setAttribute("username",
            session.getAttribute("username"));
        request.setAttribute("fullName",
            session.getAttribute("fullName"));
        request.setAttribute("role",
            session.getAttribute("role"));

        request.getRequestDispatcher("/Withdraw.jsp")
               .forward(request, response);
    }

    /**
     * POST /Withdraw — process the withdrawal amount.
     *
     * v13 addition: after successful withdrawal, attempt to send
     * a confirmation email via NotificationService.
     * The email call is in its own try-catch — if it throws for
     * any reason (SMTP unreachable, bad credentials, DB error
     * fetching user email), the failure is logged and execution
     * continues to the PRG redirect. The user sees the success
     * page either way. Mail failure never rolls back the transaction.
     */
    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws ServletException, IOException {

        // ── Session Guard ──
        HttpSession session = request.getSession(false);
        if (session == null ||
                session.getAttribute("userId") == null) {
            response.sendRedirect(
                request.getContextPath() + "/Login");
            return;
        }

        int userId    = (Integer) session.getAttribute("userId");
        String amount = request.getParameter("amount");

        try {
            Account updated = withdrawService.withdraw(userId, amount);

            log("WithdrawServlet: Withdrawal successful. " +
                "userId=" + userId +
                " amount=" + amount +
                " newBalance=" + updated.getFormattedBalance());

            // ── v13: Fire-and-forget notification ─────────────────────
            // Rule: mail failure must NEVER affect the transaction result.
            // This block is completely isolated from the withdrawal logic.
            // If anything inside this block throws — JNDI lookup failure,
            // SMTP connection refused, bad App Password, missing user email
            // in DB — the exception is caught here, logged, and execution
            // falls through to the redirect below.
            // The user always sees the withdrawal success page.
            try {
                NotificationService notificationService =
                    new NotificationService();
                notificationService.sendWithdrawEmail(
                    userId,
                    amount != null ? amount.trim() : "0",
                    updated.getFormattedBalance());

                log("WithdrawServlet: Notification sent. userId=" + userId);

                // Increment the session notification count.
                // Dashboard.jsp reads this to show the bell badge count.
                // Resets to zero when the session expires (30 min idle)
                // or on logout — this is intentional for v13 scope.
                Integer currentCount =
                    (Integer) session.getAttribute("notificationCount");
                session.setAttribute("notificationCount",
                    (currentCount != null ? currentCount : 0) + 1);

            } catch (Exception mailEx) {
                // Log the full cause so Sprint 4 / fault diagnosis has
                // something actionable in SystemOut.log to look at.
                log("WithdrawServlet: Notification FAILED (non-fatal). " +
                    "userId=" + userId +
                    " — " + mailEx.getMessage(), mailEx);
                // No re-throw. No error redirect. Execution continues below.
            }
            // ── End notification block ─────────────────────────────────

            String encoded =
                java.net.URLEncoder.encode(
                    amount != null ? amount.trim() : "", "UTF-8");

            response.sendRedirect(
                request.getContextPath() +
                "/Withdraw?result=success&amount=" + encoded);

        } catch (IllegalArgumentException e) {
            log("WithdrawServlet: Validation error. userId=" +
                userId + " — " + e.getMessage());
            redirectWithError(request, response, e.getMessage());

        } catch (InsufficientFundsException e) {
            log("WithdrawServlet: Insufficient funds. userId=" +
                userId +
                " requested=" + e.getRequestedAmount() +
                " balance="   + e.getCurrentBalance());
            redirectWithError(request, response,
                "Insufficient funds. Your balance is " +
                e.getCurrentBalance().toPlainString() +
                " and you requested " +
                e.getRequestedAmount().toPlainString() + ".");

        } catch (IllegalStateException e) {
            log("WithdrawServlet: State error. userId=" +
                userId + " — " + e.getMessage());
            redirectWithError(request, response, e.getMessage());

        } catch (SQLException e) {
            log("WithdrawServlet: DB error. userId=" +
                userId + " — " + e.getMessage(), e);
            redirectWithError(request, response,
                "A system error occurred. Please try again.");
        }
    }

    /**
     * Redirects to GET /Withdraw with a URL-encoded error message.
     * Unchanged from v6.
     */
    private void redirectWithError(HttpServletRequest request,
                                   HttpServletResponse response,
                                   String message)
            throws IOException {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(message, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = "An+error+occurred.";
        }
        response.sendRedirect(
            request.getContextPath() + "/Withdraw?error=" + encoded);
    }
}