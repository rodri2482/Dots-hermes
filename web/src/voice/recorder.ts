/**
 * Enregistrement d'un vocal au micro, comme VoiceRecorder.kt de l'app : chrono, niveaux pour l'onde
 * en direct, enveloppe finale de 40 barres. Le format dépend du navigateur (WebM/Opus sur Chrome et
 * Firefox récents, Ogg/Opus sur les anciens Firefox, MP4/AAC sur Safari) ; le plugin accepte les trois
 * et Whisper les décode tous.
 */

export const WAVEFORM_BARS = 40;
export const MAX_VOICE_MS = 5 * 60_000;
const MIN_VOICE_MS = 600;
const SAMPLE_EVERY_MS = 60;
/** Niveaux récents affichés pendant l'enregistrement (RecordingState.levels côté app). */
const LIVE_LEVELS = 48;

const CANDIDATE_TYPES = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/mp4", "audio/webm"];

export interface RecordedVoice {
  blob: Blob;
  durationMs: number;
  waveform: number[];
}

export interface RecordingTick {
  elapsedMs: number;
  levels: number[];
}

export type RecorderError = "unsupported" | "denied" | "failed";

/** Le navigateur sait enregistrer (contexte sécurisé, MediaRecorder, getUserMedia). */
export function canRecord(): boolean {
  return (
    typeof window !== "undefined" &&
    window.isSecureContext &&
    typeof MediaRecorder !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia &&
    pickType() !== null
  );
}

function pickType(): string | null {
  if (typeof MediaRecorder === "undefined") return null;
  for (const type of CANDIDATE_TYPES) {
    try {
      if (MediaRecorder.isTypeSupported(type)) return type;
    } catch {
      /* navigateur qui lève au lieu de répondre false */
    }
  }
  return null;
}

export class VoiceRecorder {
  private stream: MediaStream | null = null;
  private recorder: MediaRecorder | null = null;
  private audio: AudioContext | null = null;
  private sampler: number | null = null;
  private chunks: Blob[] = [];
  private samples: number[] = [];
  private startedAt = 0;

  /**
   * Ouvre le micro (le navigateur demande l'autorisation la première fois) et démarre.
   * [onTick] reçoit le chrono et les niveaux récents ; [onLimit] est appelé à 5 minutes.
   */
  async start(onTick: (tick: RecordingTick) => void, onLimit: () => void): Promise<RecorderError | null> {
    if (this.recorder) return null;
    const type = pickType();
    if (!type || !navigator.mediaDevices?.getUserMedia) return "unsupported";
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (e) {
      const name = (e as DOMException)?.name;
      return name === "NotAllowedError" || name === "SecurityError" ? "denied" : "failed";
    }
    try {
      const recorder = new MediaRecorder(this.stream, { mimeType: type, audioBitsPerSecond: 32_000 });
      this.chunks = [];
      this.samples = [];
      recorder.ondataavailable = (e) => e.data.size > 0 && this.chunks.push(e.data);
      recorder.start(250);
      this.recorder = recorder;
      this.startedAt = performance.now();
      this.startSampling(onTick, onLimit);
      return null;
    } catch {
      this.release();
      return "failed";
    }
  }

  /** Crête du signal toutes les 60 ms ; la racine rend les voix calmes visibles (comme l'app). */
  private startSampling(onTick: (tick: RecordingTick) => void, onLimit: () => void): void {
    let analyser: AnalyserNode | null = null;
    let buffer: Float32Array<ArrayBuffer> | null = null;
    try {
      const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      this.audio = new Ctx();
      // Créé après l'`await` du micro : certains navigateurs le démarrent suspendu (autoplay).
      if (this.audio.state === "suspended") void this.audio.resume().catch(() => {});
      analyser = this.audio.createAnalyser();
      analyser.fftSize = 1024;
      this.audio.createMediaStreamSource(this.stream!).connect(analyser);
      buffer = new Float32Array(analyser.fftSize);
    } catch {
      // Pas d'onde en direct : l'enregistrement marche quand même, avec une enveloppe plate.
    }
    this.sampler = window.setInterval(() => {
      let peak = 0;
      if (analyser && buffer) {
        analyser.getFloatTimeDomainData(buffer);
        for (let i = 0; i < buffer.length; i++) peak = Math.max(peak, Math.abs(buffer[i]));
      }
      this.samples.push(Math.sqrt(Math.min(1, peak)));
      const elapsedMs = performance.now() - this.startedAt;
      onTick({ elapsedMs, levels: this.samples.slice(-LIVE_LEVELS) });
      if (elapsedMs >= MAX_VOICE_MS) onLimit();
    }, SAMPLE_EVERY_MS);
  }

  /** Termine et renvoie le vocal ; null s'il est trop court (appui accidentel) ou en échec. */
  stop(): Promise<RecordedVoice | null> {
    const recorder = this.recorder;
    if (!recorder) return Promise.resolve(null);
    const durationMs = Math.min(performance.now() - this.startedAt, MAX_VOICE_MS);
    const waveform = downsample(this.samples, WAVEFORM_BARS);
    return new Promise((resolve) => {
      recorder.onstop = () => {
        const blob = new Blob(this.chunks, { type: recorder.mimeType || pickType() || "audio/webm" });
        this.release();
        resolve(durationMs < MIN_VOICE_MS || blob.size === 0 ? null : { blob, durationMs, waveform });
      };
      recorder.onerror = () => {
        this.release();
        resolve(null);
      };
      try {
        recorder.stop();
      } catch {
        this.release();
        resolve(null);
      }
    });
  }

  cancel(): void {
    const recorder = this.recorder;
    if (recorder) {
      recorder.ondataavailable = null;
      recorder.onstop = null;
      try {
        recorder.stop();
      } catch {
        /* déjà arrêté */
      }
    }
    this.release();
  }

  private release(): void {
    if (this.sampler != null) clearInterval(this.sampler);
    this.sampler = null;
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    void this.audio?.close().catch(() => {});
    this.audio = null;
    this.recorder = null;
    this.chunks = [];
  }
}

/** Moyenne par paquets puis normalisation sur la crête (VoiceRecorder.kt `downsample`). */
export function downsample(values: number[], bars: number): number[] {
  if (values.length === 0) return Array.from({ length: bars }, () => 0.08);
  const out = Array.from({ length: bars }, (_, i) => {
    const from = Math.floor((i * values.length) / bars);
    const to = Math.max(from + 1, Math.floor(((i + 1) * values.length) / bars));
    const slice = values.slice(from, Math.min(to, values.length));
    return slice.reduce((a, b) => a + b, 0) / slice.length;
  });
  const peak = Math.max(...out) || 1;
  return out.map((v) => Math.round(Math.min(1, Math.max(0.08, v / peak)) * 1000) / 1000);
}
