class CarAudioPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.capacity = 16384;
    this.left = new Float32Array(this.capacity);
    this.right = new Float32Array(this.capacity);
    this.read = 0;
    this.write = 0;
    this.available = 0;
    this.started = false;
    this.fraction = 0;
    this.step = 48000 / sampleRate;
    this.port.onmessage = event => {
      const data = new DataView(event.data);
      const frames = Math.floor(data.byteLength / 4);
      for (let i = 0; i < frames; i++) {
        if (this.available >= 8192) {
          this.read = (this.read + 1) % this.capacity;
          this.available--;
        }
        this.left[this.write] = data.getInt16(i * 4, true) / 32768;
        this.right[this.write] = data.getInt16(i * 4 + 2, true) / 32768;
        this.write = (this.write + 1) % this.capacity;
        this.available++;
      }
    };
  }
  process(inputs, outputs) {
    const left = outputs[0][0], right = outputs[0][1] || left;
    if (!this.started && this.available >= 2048) this.started = true;
    for (let i = 0; i < left.length; i++) {
      if (this.started && this.available >= 2) {
        const next = (this.read + 1) % this.capacity;
        left[i] = this.left[this.read] + (this.left[next] - this.left[this.read]) * this.fraction;
        right[i] = this.right[this.read] + (this.right[next] - this.right[this.read]) * this.fraction;
        this.fraction += this.step;
        const consumed = Math.min(Math.floor(this.fraction), this.available - 1);
        this.read = (this.read + consumed) % this.capacity;
        this.available -= consumed;
        this.fraction -= consumed;
      } else {
        left[i] = 0;
        right[i] = 0;
      }
    }
    if (this.available < 2) { this.started = false; this.fraction = 0; }
    return true;
  }
}
registerProcessor('car-audio-player', CarAudioPlayer);
