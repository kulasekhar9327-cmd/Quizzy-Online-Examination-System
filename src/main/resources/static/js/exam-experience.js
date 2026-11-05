(function () {
    "use strict";

    const root = document.querySelector("[data-exam-mode]");
    if (!root) {
        return;
    }

    const preface = root.querySelector("[data-exam-preface]");
    const workspace = root.querySelector("[data-exam-workspace]");
    const securityPanel = root.querySelector("[data-exam-monitor]");
    const securitySlot = root.querySelector("[data-exam-security-slot]");
    const continueButton = root.querySelector("[data-exam-continue]");
    const entryMessage = root.querySelector("[data-exam-entry-message]");
    const workspaceEntryMessage =
        root.querySelector("[data-workspace-entry-message]");
    const form = root.querySelector("form[data-exam-form]");
    const questionCards = form
        ? Array.from(form.querySelectorAll("[data-exam-question]"))
        : [];
    const previousButton = root.querySelector("[data-question-previous]");
    const nextButton = root.querySelector("[data-question-next]");
    const currentQuestion = root.querySelector("[data-current-question]");
    const questionCount = root.querySelector("[data-question-count]");
    const progressBar = root.querySelector("[data-exam-progress]");
    const questionNavigation = root.querySelector("[data-question-navigation]");
    let activeQuestion = 0;
    let workspaceStarted = false;
    let fullscreenWasEntered = Boolean(document.fullscreenElement);
    let submissionStarted = false;

    if (!preface || !workspace || !form || questionCards.length === 0) {
        return;
    }

    const submissionStatus = document.createElement("p");
    submissionStatus.className = "exam-submission-status";
    submissionStatus.setAttribute("role", "status");
    submissionStatus.setAttribute("aria-live", "assertive");
    submissionStatus.hidden = true;
    submissionStatus.textContent = "Submitting your examination...";
    workspace.insertBefore(submissionStatus, workspace.firstChild);

    function startSubmission() {
        if (submissionStarted) {
            return;
        }

        form.requestSubmit();
    }

    form.addEventListener("submit", function (event) {
        if (submissionStarted) {
            event.preventDefault();
            event.stopImmediatePropagation();
            return;
        }

        submissionStarted = true;
        submissionStatus.hidden = false;
        form.querySelectorAll('button[type="submit"], input[type="submit"]')
            .forEach(function (button) {
                button.disabled = true;
                if (button.tagName === "BUTTON") {
                    button.textContent = "Submitting...";
                } else {
                    button.value = "Submitting...";
                }
            });
    }, true);

    function showWorkspace() {
        if (workspaceStarted) {
            return;
        }
        workspaceStarted = true;
        preface.hidden = true;
        workspace.hidden = false;
        if (form.parentElement !== workspace) {
            workspace.appendChild(form);
        }
        form.hidden = false;
        document.body.classList.add("exam-mode-active");
        if (securitySlot && securityPanel) {
            securitySlot.appendChild(securityPanel);
        }
        showQuestion(0);
    }

    function updateProgress() {
        const total = questionCards.length;
        const current = activeQuestion + 1;
        if (currentQuestion) {
            currentQuestion.textContent = String(current);
        }
        if (questionCount) {
            questionCount.textContent = String(total);
        }
        if (progressBar) {
            progressBar.style.width = (current / total * 100) + "%";
            progressBar.setAttribute("aria-valuenow", String(current));
            progressBar.setAttribute("aria-valuemax", String(total));
        }
        if (previousButton) {
            previousButton.disabled = activeQuestion === 0;
        }
        if (nextButton) {
            nextButton.disabled = activeQuestion === total - 1;
        }
        if (questionNavigation) {
            Array.from(questionNavigation.children).forEach(function (button, index) {
                if (index === activeQuestion) {
                    button.setAttribute("aria-current", "step");
                } else {
                    button.removeAttribute("aria-current");
                }
            });
        }
    }

    function showQuestion(index) {
        if (index < 0 || index >= questionCards.length) {
            return;
        }
        activeQuestion = index;
        questionCards.forEach(function (card, cardIndex) {
            card.hidden = cardIndex !== activeQuestion;
        });
        updateProgress();
    }

    if (questionNavigation) {
        questionCards.forEach(function (_, index) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = String(index + 1);
            button.setAttribute("aria-label", "Go to question " + (index + 1));
            button.addEventListener("click", function () {
                showQuestion(index);
            });
            questionNavigation.appendChild(button);
        });
    }

    if (previousButton) {
        previousButton.addEventListener("click", function () {
            showQuestion(activeQuestion - 1);
        });
    }
    if (nextButton) {
        nextButton.addEventListener("click", function () {
            showQuestion(activeQuestion + 1);
        });
    }

    document.addEventListener("quizzy:fullscreen-state", function (event) {
        const details = event.detail || {};
        if (details.active) {
            fullscreenWasEntered = true;
            showWorkspace();
            if (entryMessage) {
                entryMessage.textContent = "";
            }
            if (workspaceEntryMessage) {
                workspaceEntryMessage.hidden = true;
                workspaceEntryMessage.textContent = "";
            }
        } else if (details.exited
                && fullscreenWasEntered
                && workspaceStarted) {
            if (workspaceEntryMessage) {
                workspaceEntryMessage.hidden = false;
                workspaceEntryMessage.textContent =
                    "Fullscreen exited. Submitting your examination...";
            } else {
                submissionStatus.hidden = false;
            }
            startSubmission();
        } else if (details.error && entryMessage) {
            entryMessage.textContent =
                "Fullscreen was not activated. You can try again or continue without fullscreen.";
            if (continueButton) {
                continueButton.hidden = false;
            }
        }
    });

    if (continueButton) {
        continueButton.addEventListener("click", function () {
            showWorkspace();
            if (workspaceEntryMessage) {
                workspaceEntryMessage.hidden = false;
                workspaceEntryMessage.textContent =
                    "Fullscreen is not active. Continuing in the standard browser window.";
            }
        });
    }

    if (!document.fullscreenElement
            && typeof document.documentElement.requestFullscreen !== "function"
            && continueButton) {
        continueButton.hidden = false;
        if (entryMessage) {
            entryMessage.textContent =
                "Fullscreen is unavailable in this browser. You may continue in the normal window.";
        }
    }
    if (document.fullscreenElement) {
        showWorkspace();
    }

    root.addEventListener("contextmenu", function (event) {
        if (workspace.contains(event.target)) {
            event.preventDefault();
        }
    });
    root.addEventListener("dragstart", function (event) {
        if (workspace.contains(event.target)) {
            event.preventDefault();
        }
    });

})();
