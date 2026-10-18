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
let pendingAttachment = null;
const attachmentPreview = document.getElementById("attachment-preview");
const attachmentMenu = document.getElementById("attachment-menu");

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
document.getElementById("attach-toggle").addEventListener("click", () => {
    attachmentMenu.hidden = !attachmentMenu.hidden;
});
document.getElementById("attach-image").addEventListener("click", () => {
    attachmentMenu.hidden = true;
    Isen.pickImage();
});
document.getElementById("attach-file").addEventListener("click", () => {
    attachmentMenu.hidden = true;
    Isen.pickFile();
});
document.getElementById("chat-form").addEventListener("submit", event => {
    event.preventDefault();
    const field = document.getElementById("message");
    let message = field.value.trim();
    if ((!message && !pendingAttachment) || sendButton.disabled) return;
    if (pendingAttachment && !pendingAttachment.ready) {
        statusText.textContent = "Reading attachment on this device...";
        return;
    }
    const attachment = pendingAttachment;
    const displayContent = message || (attachment ? attachment.name : "");
    if (attachment && attachment.text.trim()) {
        const instruction = message || "Please analyze this attachment.";
        message = `${instruction}\n\n[Locally extracted content from ${attachment.name}]\n${attachment.text}`;
    }
    field.value = "";
    pendingAttachment = null;
    attachmentPreview.hidden = true;
    sendMessage(message, attachment ? attachment.preview : null, displayContent);
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

function sendMessage(message, attachment = null, displayContent = message) {
    if (!message || sendButton.disabled) return;
    addMessage(displayContent, "user", attachment);
    sendButton.disabled = true;
    statusText.textContent = "ISEN is thinking...";
    if (attachment) {
        Isen.sendAttachmentMessage(message, displayContent, attachment);
    } else {
        Isen.sendMessage(message);
    }
}

function addMessage(text, role, attachment = null) {
    emptyState.hidden = true;
    const element = document.createElement("div");
    element.className = `message ${role}`;
    if (attachment && attachment.startsWith("data:image/")) {
        const image = document.createElement("img");
        image.className = "message-attachment-image";
        image.src = attachment;
        image.alt = text ? `Attached image: ${text}` : "Attached image";
        element.appendChild(image);
    } else if (attachment) {
        const file = document.createElement("span");
        file.className = "message-attachment-file";
        file.textContent = attachment.replace(/^file:/, "");
        element.appendChild(file);
    }
    if (text) {
        const messageText = document.createElement("div");
        messageText.textContent = text;
        element.appendChild(messageText);
    }
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
        addMessage(message.content, message.role, message.attachment || null);
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
        statusText.textContent = `Using ${provider === "groq" ? "Groq" : "OpenAI"} for this reply.`;
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

window.onAttachmentLoading = (name, id) => {
    pendingAttachment = { name, id, text: "", preview: null, ready: false };
    attachmentPreview.textContent = `Reading ${name} on this device...`;
    attachmentPreview.hidden = false;
    statusText.textContent = "Extracting attachment content locally; nothing is uploaded.";
};

window.onAttachmentPreviewReady = (name, preview, id) => {
    if (!pendingAttachment || Number(pendingAttachment.id) !== Number(id)) return;
    pendingAttachment.preview = preview;
    pendingAttachment.ready = true;
    pendingAttachment.analyzing = true;
    renderAttachmentPreview();
    statusText.textContent = "Photo attached. You can send it now while local reading continues.";
};

window.onAttachmentReady = (name, text, preview, id) => {
    if (!pendingAttachment || Number(pendingAttachment.id) !== Number(id)) return;
    pendingAttachment = { ...pendingAttachment, name, text, preview, ready: true, analyzing: false };
    renderAttachmentPreview();
    statusText.textContent = "Attachment ready to send.";
};

function renderAttachmentPreview() {
    attachmentPreview.replaceChildren();
    if (pendingAttachment.preview && pendingAttachment.preview.startsWith("data:image/")) {
        const image = document.createElement("img");
        image.src = pendingAttachment.preview;
        image.alt = `Selected image: ${pendingAttachment.name}`;
        attachmentPreview.appendChild(image);
    }
    const label = document.createElement("span");
    label.textContent = `${pendingAttachment.name} · ${pendingAttachment.analyzing ? "reading" : "ready"}`;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "attachment-remove";
    remove.textContent = "×";
    remove.setAttribute("aria-label", "Remove attachment");
    remove.addEventListener("click", () => {
        pendingAttachment = null;
        attachmentPreview.hidden = true;
    });
    attachmentPreview.append(label, remove);
    attachmentPreview.hidden = false;
}

window.onAttachmentError = (message, id) => {
    if (!pendingAttachment || Number(pendingAttachment.id) !== Number(id)) return;
    if (pendingAttachment.preview && pendingAttachment.preview.startsWith("data:image/")) {
        pendingAttachment.ready = true;
        pendingAttachment.analyzing = false;
        renderAttachmentPreview();
        statusText.textContent = `${message} The photo is attached and can still be sent.`;
        return;
    }
    pendingAttachment = null;
    attachmentPreview.hidden = true;
    statusText.textContent = message;
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