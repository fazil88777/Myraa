// MYRA for Windows — Gemini Live voice client + laptop tools
const { invoke } = window.__TAURI__.core;

// ---------- Settings ----------
const store = {
  get key() { return localStorage.getItem("myra_api_key") || ""; },
  get name() { return localStorage.getItem("myra_name") || "Fazil"; },
  get voice() { return localStorage.getItem("myra_voice") || "Sulafat"; },
  get model() { return localStorage.getItem("myra_model") || "gemini-2.0-flash-live-001"; },
};
const $ = (id) => document.getElementById(id);
const statusEl = $("status"), logEl = $("log"), orbEl = $("orb");

$("apiKey").value = store.key;
$("userName").value = store.name;
$("voiceName").value = store.voice;
$("modelName").value = store.model;
$("gearBtn").onclick = () => $("settingsPanel").classList.toggle("hidden");
$("saveSettings").onclick = () => {
  localStorage.setItem("myra_api_key", $("apiKey").value.trim());
  localStorage.setItem("myra_name", $("userName").value.trim() || "Fazil");
  localStorage.setItem("myra_voice", $("voiceName").value);
  localStorage.setItem("myra_model", $("modelName").value.trim() || "gemini-2.0-flash-live-001");
  $("settingsPanel").classList.add("hidden");
  addMsg("sys", "Settings save ho gayi.");
};

function setStatus(t) { statusEl.textContent = t; }
function setOrb(mode) { orbEl.className = "orb " + mode; }
function addMsg(who, text) {
  const d = document.createElement("div");
  d.className = "msg " + who;
  d.textContent = text;
  logEl.appendChild(d);
  logEl.scrollTop = logEl.scrollHeight;
  return d;
}

// ---------- Tools (laptop commands) ----------
const TOOLS = [{
  functionDeclarations: [
    { name: "google_search", description: "Google par kuch search karke Chrome mein khol do.", parameters: { type: "OBJECT", properties: { query: { type: "STRING", description: "Jo search karna hai" } }, required: ["query"] } },
    { name: "youtube_search", description: "YouTube par kuch search karke Chrome mein khol do.", parameters: { type: "OBJECT", properties: { query: { type: "STRING", description: "Jo YouTube par search karna hai" } }, required: ["query"] } },
    { name: "open_chrome", description: "Chrome browser khol do (bina kisi search ke).", parameters: { type: "OBJECT", properties: {} } },
    { name: "create_folder", description: "Desktop par naya folder banao.", parameters: { type: "OBJECT", properties: { name: { type: "STRING", description: "Folder ka naam" } }, required: ["name"] } },
    { name: "create_file", description: "Desktop par nayi text file banao.", parameters: { type: "OBJECT", properties: { name: { type: "STRING", description: "File ka naam (.txt khud lag jayega)" } }, required: ["name"] } },
    { name: "move_item", description: "Desktop par mojood kisi file ya folder ko doosre folder mein move karo. Sirf naam do, poora path nahi.", parameters: { type: "OBJECT", properties: { source: { type: "STRING", description: "Jo move karna hai (naam)" }, destination: { type: "STRING", description: "Jis folder mein move karna hai (naam)" } }, required: ["source", "destination"] } },
    { name: "copy_item", description: "Desktop par mojood kisi file ya folder ki copy doosre folder mein banao. Sirf naam do, poora path nahi.", parameters: { type: "OBJECT", properties: { source: { type: "STRING", description: "Jis ki copy karni hai (naam)" }, destination: { type: "STRING", description: "Jis folder mein copy karni hai (naam)" } }, required: ["source", "destination"] } },
    { name: "open_app", description: "Laptop ki koi app khol do.", parameters: { type: "OBJECT", properties: { app: { type: "STRING", enum: ["notepad", "calculator", "paint", "explorer", "cmd"], description: "App ka naam" } }, required: ["app"] } },
    { name: "set_volume", description: "Laptop ki awaz tez, kam ya band karo.", parameters: { type: "OBJECT", properties: { action: { type: "STRING", enum: ["up", "down", "mute"], description: "up = tez, down = kam, mute = band" } }, required: ["action"] } },
    { name: "take_screenshot", description: "Screen ka screenshot lo (Desktop/Screenshots mein save hota hai).", parameters: { type: "OBJECT", properties: {} } },
    { name: "get_time", description: "Abhi ka waqt (time) janna ho to ye use karo.", parameters: { type: "OBJECT", properties: {} } },
  ],
}];

async function runTool(name, args) {
  args = args || {};
  try {
    switch (name) {
      case "google_search":
        return await invoke("open_chrome", { url: "https://www.google.com/search?q=" + encodeURIComponent(args.query || "") });
      case "youtube_search":
        return await invoke("open_chrome", { url: "https://www.youtube.com/results?search_query=" + encodeURIComponent(args.query || "") });
      case "open_chrome":
        return await invoke("open_chrome", { url: null });
      case "create_folder":
        return await invoke("create_folder", { name: String(args.name || "") });
      case "create_file":
        return await invoke("create_file", { name: String(args.name || "") });
      case "move_item":
        return await invoke("move_item", { source: String(args.source || ""), destination: String(args.destination || "") });
      case "copy_item":
        return await invoke("copy_item", { source: String(args.source || ""), destination: String(args.destination || "") });
      case "open_app":
        return await invoke("open_app", { app: String(args.app || "") });
      case "set_volume":
        return await invoke("set_volume", { action: String(args.action || "") });
      case "take_screenshot":
        return await invoke("take_screenshot", {});
      case "get_time": {
        const d = new Date();
        let h = d.getHours(); const m = d.getMinutes();
        const ap = h >= 12 ? "dopahar/raat" : "subah";
        h = h % 12 || 12;
        return `Abhi time hai ${h} baj kar ${m} minute (${ap}).`;
      }
      default:
        return "Ye tool mojood nahi hai.";
    }
  } catch (e) {
    return "Error: " + String(e);
  }
}

// ---------- Audio helpers ----------
function floatTo16PCM(float32) {
  const out = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i++) {
    const s = Math.max(-1, Math.min(1, float32[i]));
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return out;
}
function downsample(buffer, inRate, outRate) {
  if (inRate === outRate) return buffer;
  const ratio = inRate / outRate;
  const len = Math.round(buffer.length / ratio);
  const out = new Float32Array(len);
  let o = 0, i = 0;
  while (o < len) {
    const next = Math.round((o + 1) * ratio);
    let sum = 0, count = 0;
    for (; i < next && i < buffer.length; i++) { sum += buffer[i]; count++; }
    out[o] = sum / (count || 1);
    o++;
  }
  return out;
}
function bytesToBase64(bytes) {
  let bin = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(bin);
}
function base64ToBytes(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

// ---------- Live session ----------
let ws = null, connected = false;
let micCtx = null, micStream = null, micNode = null, micSource = null;
let outCtx = null, nextPlayTime = 0, liveSources = [];
let userBubble = null, myraBubble = null;

function playPCM(b64) {
  if (!outCtx) outCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 24000 });
  const bytes = base64ToBytes(b64);
  const int16 = new Int16Array(bytes.buffer, 0, Math.floor(bytes.length / 2));
  const buf = outCtx.createBuffer(1, int16.length, 24000);
  const data = buf.getChannelData(0);
  for (let i = 0; i < int16.length; i++) data[i] = int16[i] / 32768;
  const src = outCtx.createBufferSource();
  src.buffer = buf;
  src.connect(outCtx.destination);
  const now = outCtx.currentTime;
  if (nextPlayTime < now) nextPlayTime = now + 0.05;
  src.start(nextPlayTime);
  nextPlayTime += buf.duration;
  liveSources.push(src);
  src.onended = () => {
    const i = liveSources.indexOf(src);
    if (i >= 0) liveSources.splice(i, 1);
    if (liveSources.length === 0 && connected) setOrb("listening");
  };
  setOrb("speaking");
}

function stopPlayback() {
  liveSources.forEach((s) => { try { s.stop(); } catch (e) {} });
  liveSources = [];
  nextPlayTime = 0;
}

async function startMic() {
  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    addMsg("sys", "Mic nahi mila — neeche likh kar bhi command de sakte ho.");
    return;
  }
  micCtx = new (window.AudioContext || window.webkitAudioContext)();
  micSource = micCtx.createMediaStreamSource(micStream);
  micNode = micCtx.createScriptProcessor(4096, 1, 1);
  micSource.connect(micNode);
  micNode.connect(micCtx.destination);
  micNode.onaudioprocess = (ev) => {
    if (!connected || !ws || ws.readyState !== WebSocket.OPEN) return;
    const input = ev.inputBuffer.getChannelData(0);
    const down = downsample(input, micCtx.sampleRate, 16000);
    const pcm = floatTo16PCM(down);
    const b64 = bytesToBase64(new Uint8Array(pcm.buffer));
    ws.send(JSON.stringify({ realtimeInput: { mediaChunks: [{ mimeType: "audio/pcm;rate=16000", data: b64 }] } }));
  };
}

function stopMic() {
  try { if (micNode) micNode.disconnect(); } catch (e) {}
  try { if (micSource) micSource.disconnect(); } catch (e) {}
  try { if (micStream) micStream.getTracks().forEach((t) => t.stop()); } catch (e) {}
  try { if (micCtx) micCtx.close(); } catch (e) {}
  micNode = micSource = micStream = micCtx = null;
}

function systemPrompt() {
  const n = store.name;
  return [
    `Tum MYRA ho, ${n} ki personal AI assistant, bilkul waisi jaisi us ke Android phone par ho.`,
    `Tum ${n} se Roman Urdu mein pyar aur narmi se baat karti ho, short aur natural jawab deti ho.`,
    "Jab wo laptop ka koi kaam bole (search karna, folder/file banana, file move ya copy karna, app kholna, volume, screenshot, time) to hamesha diye gaye tools (functions) use karo — sirf kehna nahi, asal mein tool call karke kaam karo.",
    "Tool ka result milne ke baad ek chhoti si tasdeeq do, jaise 'Ho gaya " + n + "'.",
    "Agar koi kaam tumhare tools mein nahi hai to pyar se sach bata do ke ye abhi nahi ho sakta.",
    "Baat-cheet mein usey naam lekar bulao. Jawab chhote rakho kyunki ye awaz mein suna jayega.",
  ].join("\n");
}

function connect() {
  const key = store.key;
  if (!key) {
    $("settingsPanel").classList.remove("hidden");
    setStatus("Pehle Settings (⚙️) mein apni Gemini API key lagao");
    return;
  }
  setStatus("Connect ho rahi hoon…");
  const url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + encodeURIComponent(key);
  ws = new WebSocket(url);

  ws.onopen = () => {
    ws.send(JSON.stringify({
      setup: {
        model: "models/" + store.model,
        generationConfig: {
          responseModalities: ["AUDIO"],
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: store.voice } } },
        },
        systemInstruction: { parts: [{ text: systemPrompt() }] },
        tools: TOOLS,
        inputAudioTranscription: {},
        outputAudioTranscription: {},
      },
    }));
  };

  ws.onmessage = async (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch (e) { return; }

    if (msg.setupComplete) {
      connected = true;
      setOrb("listening");
      setStatus("Sun rahi hoon — bolo " + store.name + "…");
      await startMic();
      // Pehla salam MYRA ki taraf se
      ws.send(JSON.stringify({
        clientContent: {
          turns: [{ role: "user", parts: [{ text: "(Session shuru hua hai — mujhe salam karo aur poochho kya kaam hai, ek line mein)" }] }],
          turnComplete: true,
        },
      }));
      return;
    }

    const sc = msg.serverContent;
    if (sc) {
      if (sc.inputTranscription && sc.inputTranscription.text) {
        if (!userBubble) userBubble = addMsg("user", "");
        userBubble.textContent += sc.inputTranscription.text;
        logEl.scrollTop = logEl.scrollHeight;
      }
      if (sc.outputTranscription && sc.outputTranscription.text) {
        if (!myraBubble) myraBubble = addMsg("myra", "");
        myraBubble.textContent += sc.outputTranscription.text;
        logEl.scrollTop = logEl.scrollHeight;
      }
      if (sc.modelTurn && sc.modelTurn.parts) {
        for (const part of sc.modelTurn.parts) {
          if (part.inlineData && part.inlineData.data) playPCM(part.inlineData.data);
        }
      }
      if (sc.interrupted) stopPlayback();
      if (sc.turnComplete) { userBubble = null; myraBubble = null; }
    }

    if (msg.toolCall && msg.toolCall.functionCalls) {
      const responses = [];
      for (const fc of msg.toolCall.functionCalls) {
        const result = await runTool(fc.name, fc.args);
        responses.push({ id: fc.id, name: fc.name, response: { result: String(result) } });
      }
      ws.send(JSON.stringify({ toolResponse: { functionResponses: responses } }));
    }
  };

  ws.onerror = () => setStatus("Connection mein masla — internet ya API key check karo");
  ws.onclose = () => {
    connected = false;
    ws = null;
    stopMic();
    stopPlayback();
    setOrb("idle");
    setStatus("Band ho gayi — orb dabao dobara shuru karne ke liye");
  };
}

function disconnect() {
  if (ws) { try { ws.close(); } catch (e) {} ws = null; }
}

orbEl.onclick = () => { connected || ws ? disconnect() : connect(); };

function sendText() {
  const v = $("textInput").value.trim();
  if (!v) return;
  $("textInput").value = "";
  if (!connected || !ws || ws.readyState !== WebSocket.OPEN) {
    addMsg("sys", "Pehle orb daba kar connect karo, phir likho.");
    return;
  }
  addMsg("user", v);
  ws.send(JSON.stringify({
    clientContent: { turns: [{ role: "user", parts: [{ text: v }] }], turnComplete: true },
  }));
}
$("sendBtn").onclick = sendText;
$("textInput").addEventListener("keydown", (e) => { if (e.key === "Enter") sendText(); });
