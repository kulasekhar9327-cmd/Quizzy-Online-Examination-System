(function () {
    "use strict";

    const MAX_WARNINGS = 5;
    const WARNING_STORAGE_KEY = "quizzy.examWarnings";
    const RELATED_EVENT_WINDOW_MS = 250;
    const startForm = document.querySelector("form[data-exam-start-form]");

    function clearStoredWarnings() {
        try {
            sessionStorage.removeItem(WARNING_STORAGE_KEY);
        } catch (error) {
            if (error.name !== "SecurityError") {
                throw error;
            }
        }
    }

    if (startForm) {
        startForm.addEventListener("submit", function () {
            clearStoredWarnings();
        });
    }

    const panel = document.querySelector("[data-exam-monitor]");
    const examForm = document.querySelector("form[data-exam-form]");

    if (!panel || !examForm) {
        return;
    }

    const warningCount = panel.querySelector("[data-warning-count]");
    const warningMessage = panel.querySelector("[data-warning-message]");
    const fullscreenStatus = panel.querySelector("[data-fullscreen-status]");
    const fullscreenButton = panel.querySelector("[data-fullscreen-button]");
    const warningLimit = panel.querySelector("[data-warning-limit]");
    const pendingWarningStatus =
        panel.querySelector("[data-pending-warning-status]");
    const pendingWarningCount =
        panel.querySelector("[data-pending-warning-count]");
    const monitoringStatus = panel.querySelector("[data-monitoring-status]");
    const attemptInput = examForm.querySelector('input[name="attemptId"]');
    const attemptId = attemptInput ? attemptInput.value : "";
    const pendingStorageKey =
        "quizzy.examMonitoring.pending." + attemptId;
    const MAX_SEND_ATTEMPTS = 3;
    const RETRY_DELAYS_MS = [1000, 3000];
    let pendingStorageAvailable = true;
    let confirmedWarnings = 0;
    let attemptDisqualified = false;
    let pendingIncidents = loadPendingIncidents();
    let windowFocused = document.hasFocus();
    let interruptionActive = document.visibilityState === "hidden"
        || !windowFocused;
    let fullscreenWasActive = Boolean(document.fullscreenElement);
    let fullscreenExitAt = 0;

    function showMessage(message, severity) {
        warningMessage.textContent = message;
        warningMessage.dataset.severity = severity || "info";
    }

    function loadPendingIncidents() {
        try {
            const stored = JSON.parse(
                sessionStorage.getItem(pendingStorageKey) || "[]");
            if (!Array.isArray(stored)) {
                return [];
            }
            return stored.filter(function (incident) {
                return incident
                    && typeof incident.clientEventId === "string"
                    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
                        .test(incident.clientEventId)
                    && [
                        "TAB_SWITCH",
                        "FOCUS_LOSS",
                        "FULLSCREEN_EXIT",
                        "COPY",
                        "CUT",
                        "PASTE"
                    ].includes(incident.violationType);
            }).slice(0, MAX_WARNINGS);
        } catch (error) {
            if (error.name === "SecurityError") {
                pendingStorageAvailable = false;
                return [];
            }
            try {
                sessionStorage.removeItem(pendingStorageKey);
            } catch (storageError) {
                if (storageError.name !== "SecurityError") {
                    throw storageError;
                }
            }
            return [];
        }
    }

    function savePendingIncidents() {
        try {
            sessionStorage.setItem(
                pendingStorageKey,
                JSON.stringify(pendingIncidents));
            pendingStorageAvailable = true;
        } catch (error) {
            if (error.name !== "SecurityError") {
                throw error;
            }
            pendingStorageAvailable = false;
            monitoringStatus.textContent =
                "Monitoring is active, but this browser cannot save pending "
                + "alerts for a connection retry.";
        }
    }

    function updateWarningDisplay() {
        warningCount.textContent = String(confirmedWarnings);
        pendingWarningCount.textContent = String(pendingIncidents.length);
        pendingWarningStatus.hidden = pendingIncidents.length === 0;
        if (confirmedWarnings === 0) {
            return;
        }

        const messages = [
            "Warning 1 of 5 has been reached. Please follow the exam instructions.",
            "Warning 2 of 5: repeated incidents have been observed.",
            "Serious caution — warning 3 of 5 has been reached.",
            "Final warning — warning 4 of 5. The next warning reaches the maximum.",
            "The server confirmed warning 5 of 5."
        ];
        showMessage(messages[confirmedWarnings - 1], confirmedWarnings >= 3
            ? "error"
            : "warning");
    }

    function updateConfirmedWarnings(value) {
        if (!Number.isInteger(value) || value < 0 || value > MAX_WARNINGS) {
            return false;
        }
        confirmedWarnings = Math.max(confirmedWarnings, value);
        updateWarningDisplay();
        return true;
    }

    function showDisqualification(message) {
        if (attemptDisqualified) {
            return;
        }
        attemptDisqualified = true;
        updateConfirmedWarnings(MAX_WARNINGS);
        showMessage(
            message || "This attempt was disqualified after reaching the "
                + "monitoring warning limit. Browser events are observations, "
                + "not proof of misconduct.",
            "error");
        monitoringStatus.textContent =
            "The server confirmed that this attempt is disqualified.";
        examForm.querySelectorAll("input, button, select, textarea")
            .forEach(function (control) {
                control.disabled = true;
            });
        examForm.addEventListener("submit", function (event) {
            event.preventDefault();
        }, true);
        window.dispatchEvent(new Event("quizzy:attempt-disqualified"));
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = {
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest"
        };
        if (token && header && token.content && header.content) {
            headers[header.content] = token.content;
        }
        return headers;
    }

    function removePendingIncident(clientEventId) {
        pendingIncidents = pendingIncidents.filter(function (incident) {
            return incident.clientEventId !== clientEventId;
        });
        savePendingIncidents();
        updateWarningDisplay();
    }

    function sendPendingIncident(incident, attemptNumber) {
        if (!attemptId) {
            monitoringStatus.textContent =
                "An alert is pending, but the active exam attempt is missing.";
            return;
        }

        monitoringStatus.textContent = "Saving a detected monitoring alert…";
        fetch("/api/exam-monitoring/violations", {
            method: "POST",
            credentials: "same-origin",
            headers: csrfHeaders(),
            body: JSON.stringify({
                attemptId: attemptId,
                clientEventId: incident.clientEventId,
                violationType: incident.violationType
            })
        }).then(function (response) {
            return response.json().catch(function () {
                return {};
            }).then(function (body) {
                return {response: response, body: body};
            });
        }).then(function (result) {
            const response = result.response;
            const body = result.body;

            if (response.status === 401 || response.status === 403) {
                monitoringStatus.textContent =
                    "This alert is still pending. Your session or security "
                    + "token may have expired; refresh the exam page to retry.";
                return;
            }

            if (response.status === 410) {
                if (body.disqualified
                        || body.attemptStatus === "DISQUALIFIED") {
                    showDisqualification(body.disqualificationReason);
                    removePendingIncident(incident.clientEventId);
                    return;
                }
                removePendingIncident(incident.clientEventId);
                monitoringStatus.textContent =
                    "The server rejected this alert because the attempt has "
                    + "ended. It was not saved.";
                return;
            }

            if (response.status === 429) {
                if (updateConfirmedWarnings(body.warningCount)
                        && body.warningCount === MAX_WARNINGS) {
                    removePendingIncident(incident.clientEventId);
                    monitoringStatus.textContent =
                        "The server confirmed the warning limit; this "
                        + "additional alert was not saved.";
                } else {
                    monitoringStatus.textContent =
                        "This alert is still pending and was not confirmed "
                        + "by the server.";
                }
                return;
            }

            if (response.status === 409 || response.status === 400) {
                monitoringStatus.textContent =
                    "This alert could not be confirmed by the server and "
                    + "remains pending.";
                return;
            }

            if (!response.ok
                    || !updateConfirmedWarnings(body.warningCount)) {
                throw new Error("Monitoring request was not confirmed.");
            }

            removePendingIncident(incident.clientEventId);
            if (body.disqualified
                    || body.attemptStatus === "DISQUALIFIED") {
                showDisqualification(body.disqualificationReason);
                return;
            }
            monitoringStatus.textContent = body.duplicate
                ? "The server confirmed this previously saved alert."
                : "The server confirmed and saved this observed alert.";
        }).catch(function () {
            if (attemptNumber + 1 < MAX_SEND_ATTEMPTS) {
                monitoringStatus.textContent =
                    "A monitoring alert is pending. Retrying safely…";
                window.setTimeout(function () {
                    sendPendingIncident(incident, attemptNumber + 1);
                }, RETRY_DELAYS_MS[attemptNumber]);
                return;
            }
            monitoringStatus.textContent =
                "A monitoring alert is still pending and was not confirmed. "
                + (pendingStorageAvailable
                    ? "It will be retried on refresh; the exam can continue."
                    : "This browser cannot retain it for refresh; the exam "
                        + "can continue.");
        });
    }

    function loadServerHistory() {
        if (!attemptId) {
            monitoringStatus.textContent =
                "The active attempt is unavailable; monitoring alerts cannot "
                + "be confirmed.";
            return;
        }

        fetch(
            "/api/exam-monitoring/violations/"
                + encodeURIComponent(attemptId),
            {credentials: "same-origin"}
        ).then(function (response) {
            if (!response.ok) {
                throw new Error("Monitoring history could not be loaded.");
            }
            return response.json();
        }).then(function (history) {
            if (!history
                    || history.attemptId !== attemptId
                    || typeof history.disqualified !== "boolean"
                    || !updateConfirmedWarnings(history.warningCount)) {
                throw new Error("Monitoring history response is invalid.");
            }
            if (history.disqualified
                    || history.attemptStatus === "DISQUALIFIED") {
                showDisqualification(history.disqualificationReason);
                return;
            }
            if (pendingIncidents.length === 0) {
                monitoringStatus.textContent =
                    "No pending events. The server-confirmed warning count "
                    + "is shown above.";
            } else {
                monitoringStatus.textContent =
                    "Retrying pending alerts with their original event IDs.";
                pendingIncidents.slice().forEach(function (incident) {
                    sendPendingIncident(incident, 0);
                });
            }
        }).catch(function () {
            monitoringStatus.textContent =
                "Monitoring history is unavailable. The exam can continue.";
            pendingIncidents.slice().forEach(function (incident) {
                sendPendingIncident(incident, 0);
            });
        });
    }

    function recordBrowserAlert(message, severity, violationType) {
        if (confirmedWarnings + pendingIncidents.length >= MAX_WARNINGS) {
            return;
        }

        if (!window.crypto || typeof window.crypto.randomUUID !== "function") {
            monitoringStatus.textContent =
                "A monitoring alert was detected but cannot be queued safely "
                + "in this browser.";
            return;
        }

        const incident = {
            clientEventId: window.crypto.randomUUID(),
            violationType: violationType
        };
        pendingIncidents.push(incident);
        savePendingIncidents();
        updateWarningDisplay();
        if (pendingStorageAvailable) {
            monitoringStatus.textContent =
                "Observed: " + message
                + " Waiting for server confirmation; this is not a "
                + "conclusion of misconduct.";
        }
        sendPendingIncident(incident, 0);
    }

    function updateFullscreenStatus() {
        const active = Boolean(document.fullscreenElement);
        fullscreenStatus.textContent = active ? "Active" : "Not active";
        fullscreenButton.textContent = active
            ? "Fullscreen is active"
            : "Enter fullscreen";
        fullscreenButton.disabled = active;
        fullscreenButton.setAttribute("aria-pressed", String(active));
    }

    function refreshInterruptionState(message, violationType) {
        const interrupted = document.visibilityState === "hidden"
            || !windowFocused;

        if (interrupted && !interruptionActive) {
            interruptionActive = true;
            if (Date.now() - fullscreenExitAt > RELATED_EVENT_WINDOW_MS) {
                recordBrowserAlert(message, "warning", violationType);
            }
        } else if (!interrupted) {
            interruptionActive = false;
        }
    }

    function enterFullscreen() {
        if (typeof document.documentElement.requestFullscreen !== "function") {
            fullscreenStatus.textContent = "Unavailable in this browser";
            showMessage(
                "Fullscreen is not supported here. You can continue the exam normally.",
                "info"
            );
            document.dispatchEvent(new CustomEvent("quizzy:fullscreen-state", {
                detail: {active: false, unavailable: true}
            }));
            return;
        }

        document.documentElement.requestFullscreen().then(function () {
            if (!document.fullscreenElement) {
                showMessage(
                    "Fullscreen was not activated. You can try again or continue without fullscreen.",
                    "warning"
                );
                document.dispatchEvent(new CustomEvent("quizzy:fullscreen-state", {
                    detail: {active: false, error: true}
                }));
            }
        }).catch(function () {
            showMessage(
                "Fullscreen could not be started. You can try again or continue without fullscreen.",
                "warning"
            );
            document.dispatchEvent(new CustomEvent("quizzy:fullscreen-state", {
                detail: {active: false, error: true}
            }));
        });
    }

    fullscreenButton.addEventListener("click", enterFullscreen);

    document.addEventListener("visibilitychange", function () {
        if (document.visibilityState === "hidden") {
            refreshInterruptionState(
                "The exam page became hidden. Please return to the exam.",
                "TAB_SWITCH"
            );
        } else {
            refreshInterruptionState("");
        }
    });

    window.addEventListener("blur", function () {
        windowFocused = false;
        refreshInterruptionState(
            "The exam window lost focus. Please return to the exam.",
            "FOCUS_LOSS"
        );
    });

    window.addEventListener("focus", function () {
        windowFocused = true;
        refreshInterruptionState("");
    });

    document.addEventListener("fullscreenchange", function () {
        const fullscreenActive = Boolean(document.fullscreenElement);
        const fullscreenExited = fullscreenWasActive && !fullscreenActive;

        if (fullscreenActive) {
            showMessage("Fullscreen is active for this exam.", "info");
        } else if (fullscreenExited) {
            showMessage(
                "Fullscreen exit detected. The examination is being submitted.",
                "info"
            );
        }
        if (fullscreenExited && !interruptionActive) {
            recordBrowserAlert(
                "Fullscreen was exited. You may re-enter using the fullscreen button.",
                "warning",
                "FULLSCREEN_EXIT"
            );
            fullscreenExitAt = Date.now();
        } else if (fullscreenExited) {
            fullscreenExitAt = Date.now();
        }

        fullscreenWasActive = fullscreenActive;
        updateFullscreenStatus();
        document.dispatchEvent(new CustomEvent("quizzy:fullscreen-state", {
            detail: {
                active: fullscreenActive,
                exited: fullscreenExited
            }
        }));
    });

    ["copy", "cut", "paste"].forEach(function (eventType) {
        examForm.addEventListener(eventType, function (event) {
            if (!event.target.closest("[data-exam-content]")) {
                return;
            }
            event.preventDefault();
            const labels = {
                copy: "Copy",
                cut: "Cut",
                paste: "Paste"
            };

            recordBrowserAlert(
                labels[eventType] + " was used in the exam area.",
                "warning",
                eventType.toUpperCase()
            );
        }, true);
    });

    examForm.addEventListener("keydown", function (event) {
        if (!event.target.closest("[data-exam-content]")
                || !(event.ctrlKey || event.metaKey)) {
            return;
        }

        const key = event.key.toLowerCase();
        const violationTypes = {
            c: "COPY",
            x: "CUT",
            v: "PASTE"
        };
        if (key === "a" || Object.prototype.hasOwnProperty.call(
                violationTypes, key)) {
            event.preventDefault();
        }
        if (Object.prototype.hasOwnProperty.call(violationTypes, key)) {
            recordBrowserAlert(
                violationTypes[key].toLowerCase()
                    + " keyboard shortcut was used in the exam area.",
                "warning",
                violationTypes[key]
            );
        }
    }, true);

    updateFullscreenStatus();
    document.dispatchEvent(new CustomEvent("quizzy:fullscreen-state", {
        detail: {active: Boolean(document.fullscreenElement)}
    }));
    warningLimit.textContent = String(MAX_WARNINGS);
    updateWarningDisplay();
    monitoringStatus.textContent =
        "Checking the server for this attempt's confirmed warning count.";
    loadServerHistory();

    if (typeof document.documentElement.requestFullscreen !== "function") {
        fullscreenStatus.textContent = "Unavailable in this browser";
    }

})();
