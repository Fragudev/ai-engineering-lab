(function () {
  const messagesEl = document.getElementById("messages");
  const form = document.getElementById("composer");
  const input = document.getElementById("input");
  const sendButton = document.getElementById("send");

  let conversationId = null;

  function addBubble(role, text) {
    const el = document.createElement("div");
    el.className = "bubble " + role;
    el.textContent = text;
    messagesEl.appendChild(el);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return el;
  }

  function addMeta(parts) {
    const el = document.createElement("div");
    el.className = "meta";
    el.append(...parts);
    messagesEl.appendChild(el);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  async function ensureConversation() {
    if (conversationId) return conversationId;
    const response = await fetch("/api/v1/conversations", { method: "POST" });
    if (!response.ok) throw new Error("Could not create a conversation");
    const body = await response.json();
    conversationId = body.id;
    return conversationId;
  }

  // Native EventSource only supports GET; this endpoint takes a POST body, so the
  // text/event-stream framing is parsed by hand from a fetch() ReadableStream.
  async function streamReply(id, content, handlers) {
    const response = await fetch(`/api/v1/conversations/${id}/messages`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ content }),
    });

    if (!response.ok || !response.body) {
      const problem = await response.json().catch(() => null);
      handlers.onError(problem || { detail: response.statusText });
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let separatorIndex;
      while ((separatorIndex = buffer.indexOf("\n\n")) !== -1) {
        const rawEvent = buffer.slice(0, separatorIndex);
        buffer = buffer.slice(separatorIndex + 2);
        dispatchEvent(rawEvent, handlers);
      }
    }
  }

  function dispatchEvent(rawEvent, handlers) {
    const nameMatch = rawEvent.match(/^event:\s*(.*)$/m);
    const eventName = nameMatch ? nameMatch[1] : "message";
    const data = rawEvent
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");

    switch (eventName) {
      case "token":
        handlers.onToken(data);
        break;
      case "usage":
        handlers.onUsage(JSON.parse(data));
        break;
      case "error":
        handlers.onError(JSON.parse(data));
        break;
      case "done":
        handlers.onDone();
        break;
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const content = input.value.trim();
    if (!content) return;

    input.value = "";
    sendButton.disabled = true;
    addBubble("user", content);
    const assistantBubble = addBubble("assistant", "");

    try {
      const id = await ensureConversation();
      await streamReply(id, content, {
        onToken: (text) => {
          assistantBubble.textContent += text;
          messagesEl.scrollTop = messagesEl.scrollHeight;
        },
        onUsage: (usage) => {
          const parts = [
            document.createTextNode(
                `${usage.model} · ${usage.promptTokens}+${usage.completionTokens} tokens · ` +
                `${usage.latencyMs} ms · $${Number(usage.estimatedCostUsd).toFixed(4)}`
            ),
          ];
          if (usage.traceId) {
            const link = document.createElement("a");
            link.href = "http://localhost:3000/explore";
            link.target = "_blank";
            link.rel = "noopener";
            link.textContent = `trace ${usage.traceId.slice(0, 12)}…`;
            parts.push(document.createTextNode(" · "), link);
          }
          addMeta(parts);
        },
        onError: (problem) => {
          assistantBubble.classList.add("error");
          assistantBubble.textContent = (problem && (problem.detail || problem.title)) || "Something went wrong.";
        },
        onDone: () => {},
      });
    } catch (err) {
      assistantBubble.classList.add("error");
      assistantBubble.textContent = "Request failed: " + err.message;
    } finally {
      sendButton.disabled = false;
      input.focus();
    }
  });
})();

// Ingestion panel: upload a document, then poll its job status until it reaches a terminal
// stage. Polling, not a real-time dashboard — consistent with the deliberately minimal UI.
(function () {
  const uploadForm = document.getElementById("uploadForm");
  const fileInput = document.getElementById("fileInput");
  const documentList = document.getElementById("documentList");
  const jobsByDocumentId = new Map();

  function renderDocuments(documents) {
    documentList.replaceChildren();
    for (const doc of documents) {
      const job = jobsByDocumentId.get(doc.id);
      const stage = job ? job.stage : doc.status;
      const row = document.createElement("div");
      row.className = "doc-row";

      const titleEl = document.createElement("span");
      titleEl.className = "doc-title";
      titleEl.textContent = doc.title;

      const badgeEl = document.createElement("span");
      badgeEl.className = "stage-badge stage-" + stage;
      badgeEl.textContent = stage + (job && job.lastError ? ": " + job.lastError : "");

      row.append(titleEl, badgeEl);
      documentList.appendChild(row);
    }
  }

  async function refreshDocuments() {
    const response = await fetch("/api/v1/documents");
    if (!response.ok) return;
    const documents = await response.json();
    renderDocuments(documents);
    return documents;
  }

  async function pollJob(jobLocation, documentId) {
    for (let attempt = 0; attempt < 30; attempt++) {
      const response = await fetch(jobLocation);
      if (!response.ok) return;
      const job = await response.json();
      jobsByDocumentId.set(documentId, job);
      const documents = await refreshDocuments();
      if (job.stage === "INDEXED" || job.stage === "FAILED") return;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }

  uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = fileInput.files[0];
    if (!file) return;

    const formData = new FormData();
    formData.append("file", file);
    formData.append("title", file.name);

    const response = await fetch("/api/v1/documents", { method: "POST", body: formData });
    fileInput.value = "";
    if (!response.ok) return;

    const document_ = await response.json();
    await refreshDocuments();

    const jobLocation = response.headers.get("Location");
    if (jobLocation) {
      pollJob(jobLocation, document_.id);
    }
  });

  refreshDocuments();
})();
