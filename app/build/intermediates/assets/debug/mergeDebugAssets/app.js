const loadingScreen = document.getElementById("loading-screen");
const setupScreen = document.getElementById("setup-screen");
const appScreen = document.getElementById("app-screen");
const settingsPanel = document.getElementById("settings");
const conversation = document.getElementById("conversation");
const emptyState = document.getElementById("empty-state");
const statusText = document.getElementById("status");
const sendButton = document.getElementById("send");
const setupStatus = document.getElementById("setup-status");
const voiceToggle = document.getElementById("voice-toggle");
const historyDrawer = document.getElementById("history-drawer");
const sessionList = document.getElementById("session-list");
let activeSessionId = 0;
let voiceActive = false;

document.getElementById("settings-toggle").addEventListener("click", () => {
    settingsPanel.hidden = false;
});
document.getElementById("settings-close").addEventListener("click", () => {
    settingsPanel.hidden = true;
});
document.getElementById("sessions-open").addEventListener("click", () => {
    historyDrawer.hidden = false;
    Isen.requestSessions();
});
document.getElementById("history-close").addEventListener("click", () => {
    historyDrawer.hidden = true;
});
document.getElementById("new-session").addEventListener("click", createSession);
document.getElementById("history-new-session").addEventListener("click", createSession);
document.getElementById("setup-form").addEventListener("submit", event => {
    event.preventDefault();
    const groqKey = document.getElementById("setup-groq-key").value.trim();
    const openAiKey = document.getElementById("setup-openai-key").value.trim();
    if (!groqKey || !openAiKey) {
        setupStatus.textContent = "Both API keys are required.";
        return;
    }
    setupStatus.textContent = "Saving keys securely...";
    Isen.saveKeys(groqKey, openAiKey);
});
document.getElementById("settings-form").addEventListener("submit", event => {
    event.preventDefault();
    Isen.saveKeys(document.getElementById("edit-groq-key").value,
        document.getElementById("edit-openai-key").value);
});
document.getElementById("clear-keys").addEventListener("click", () => Isen.clearKeys());
document.getElementById("chat-form").addEventListener("submit", event => {
    event.preventDefault();
    const field = document.getElementById("message");
    const message = field.value.trim();
    if (!message || sendButton.disabled) return;
    field.value = "";
    sendMessage(message);
});
voiceToggle.addEventListener("click", toggleVoiceMode);

function toggleVoiceMode() {
    voiceActive = !voiceActive;
    voiceToggle.classList.toggle("active", voiceActive);
    voiceToggle.setAttribute("aria-label", voiceActive ? "Stop voice chat" : "Start voice chat");
    voiceToggle.title = voiceActive ? "Stop voice chat" : "Start voice chat";
    if (voiceActive) {
        statusText.textContent = "Preparing voice chat...";
        Isen.startVoiceMode();
    } else {
        Isen.stopVoiceMode();
    }
}

function createSession() {
    historyDrawer.hidden = true;
    Isen.newSession();
}

function sendMessage(message) {
    if (!message || sendButton.disabled) return;
    addMessage(message, "user");
    sendButton.disabled = true;
    statusText.textContent = "ISEN is thinking...";
    Isen.sendMessage(message);
}

function addMessage(text, role) {
    emptyState.hidden = true;
    const element = document.createElement("div");
    element.className = `message ${role}`;
    element.textContent = text;
    conversation.appendChild(element);
    window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
}

window.onIsenState = state => {
    window.isenState = state;
    loadingScreen.hidden = true;
    setupScreen.hidden = state.configured;
    appScreen.hidden = !state.configured;
    statusText.textContent = state.configured ? "Ready when you are." : "";
    document.getElementById("key-state").textContent =
        `Groq: ${state.groqReady ? "saved" : "not set"} · OpenAI: ${state.openAiReady ? "saved" : "not set"}`;
    if (!state.configured) {
        settingsPanel.hidden = true;
    }
};

window.onSessionLoaded = session => {
    activeSessionId = Number(session.id);
    document.getElementById("active-session-title").textContent = session.title;
    renderMessages(session.messages || []);
    renderSessions(session.sessions || []);
    historyDrawer.hidden = true;
};

window.onSessionsState = sessions => {
    renderSessions(sessions);
};

function renderSessions(sessions) {
    sessionList.replaceChildren();
    for (const session of sessions) {
        const row = document.createElement("div");
        row.className = "session-row";
        const button = document.createElement("button");
        button.className = `session-item${session.active ? " active" : ""}`;
        button.type = "button";
        button.textContent = session.title;
        button.addEventListener("click", () => {
            historyDrawer.hidden = true;
            Isen.openSession(Number(session.id));
        });
        const deleteButton = document.createElement("button");
        deleteButton.className = "session-delete";
        deleteButton.type = "button";
        deleteButton.textContent = "🗑";
        deleteButton.setAttribute("aria-label", `Delete chat ${session.title}`);
        deleteButton.title = "Delete chat";
        deleteButton.addEventListener("click", () => Isen.deleteSession(Number(session.id)));
        row.append(button, deleteButton);
        sessionList.appendChild(row);
    }
}

function renderMessages(messages) {
    conversation.replaceChildren(emptyState);
    emptyState.hidden = messages.length > 0;
    for (const message of messages) {
        addMessage(message.content, message.role);
    }
}

window.onIsenKeysSaved = state => {
    document.getElementById("setup-groq-key").value = "";
    document.getElementById("setup-openai-key").value = "";
    document.getElementById("edit-groq-key").value = "";
    document.getElementById("edit-openai-key").value = "";
    window.onIsenState(state);
    settingsPanel.hidden = true;
    setupStatus.textContent = "Keys are encrypted and saved on this device.";
};

window.onIsenKeysCleared = state => {
    window.onIsenState(state);
    setupStatus.textContent = "Add both API keys to continue.";
};

window.onIsenReply = (reply, failed, provider, switched, sessionId) => {
    sendButton.disabled = false;
    if (Number(sessionId) !== activeSessionId) return;
    addMessage(reply, failed ? "assistant error" : "assistant");
    if (failed) {
        statusText.textContent = "Request could not be completed.";
    } else if (switched) {
        statusText.textContent = `Rate limit reached. Switched to ${provider === "groq" ? "Groq" : "OpenAI"}.`;
    } else {
        statusText.textContent = "Ready when you are.";
    }
};

window.onIsenError = message => {
    if (setupScreen.hidden) {
        document.getElementById("key-state").textContent = message;
    } else {
        setupStatus.textContent = message;
    }
};

window.onVoiceStatus = message => {
    statusText.textContent = message;
};

window.onVoiceResult = message => {
    sendMessage(message);
};

window.onVoiceClosed = () => {
    voiceActive = false;
    voiceToggle.classList.remove("active");
    voiceToggle.setAttribute("aria-label", "Start voice chat");
    voiceToggle.title = "Start voice chat";
};

Isen.requestState();