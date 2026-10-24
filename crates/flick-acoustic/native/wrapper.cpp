// Plain C interface over ggwave, so the Rust side needs no C++ types.
#include "ggwave/ggwave.h"

#include <cstdint>
#include <cstring>
#include <vector>

namespace {
constexpr int kSampleRate = 48000;

ggwave_Parameters make_parameters() {
    ggwave_setLogFile(nullptr);  // ggwave prints every frame to stderr otherwise
    ggwave_Parameters p = ggwave_getDefaultParameters();
    p.sampleRateInp = kSampleRate;
    p.sampleRateOut = kSampleRate;
    p.sampleRate = kSampleRate;
    p.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_F32;
    p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_F32;
    return p;
}

struct Decoder {
    ggwave_Instance instance;
    std::vector<float> pending;
    int frame;
};
}  // namespace

extern "C" {

// Writes the waveform of `payload` into `out` and returns the number of samples.
// Returns the needed size when `out` is null, and -1 on failure.
int32_t fa_encode(const uint8_t* payload, int32_t len, float* out, int32_t cap, int32_t protocol_id) {
    ggwave_Instance g = ggwave_init(make_parameters());
    if (g < 0) return -1;
    const auto protocol = static_cast<ggwave_ProtocolId>(protocol_id);
    const int volume = 25;
    int bytes = ggwave_encode(g, payload, len, protocol, volume, nullptr, 1);
    int samples = bytes / static_cast<int>(sizeof(float));
    if (bytes <= 0) {
        ggwave_free(g);
        return -1;
    }
    if (out == nullptr) {
        ggwave_free(g);
        return samples;
    }
    if (samples > cap) {
        ggwave_free(g);
        return -1;
    }
    int written = ggwave_encode(g, payload, len, protocol, volume, out, 0);
    ggwave_free(g);
    return written > 0 ? samples : -1;
}

void* fa_decoder_new() {
    ggwave_Parameters p = make_parameters();
    ggwave_Instance g = ggwave_init(p);
    if (g < 0) return nullptr;
    return new Decoder{g, {}, p.samplesPerFrame};
}

// Feeds microphone samples. Returns the length of a decoded message copied to
// `out`, 0 when nothing was decoded yet, and -1 on failure.
int32_t fa_decoder_push(void* handle, const float* samples, int32_t n, uint8_t* out, int32_t cap) {
    auto* d = static_cast<Decoder*>(handle);
    if (d == nullptr || samples == nullptr || out == nullptr) return -1;
    d->pending.insert(d->pending.end(), samples, samples + n);
    std::vector<char> buffer(256);
    size_t offset = 0;
    int32_t result = 0;
    while (d->pending.size() - offset >= static_cast<size_t>(d->frame)) {
        int len = ggwave_decode(d->instance, reinterpret_cast<const char*>(d->pending.data() + offset),
                                d->frame * static_cast<int>(sizeof(float)), buffer.data());
        offset += d->frame;
        if (len > 0 && len <= cap) {
            std::memcpy(out, buffer.data(), len);
            result = len;
        }
    }
    d->pending.erase(d->pending.begin(), d->pending.begin() + offset);
    return result;
}

void fa_decoder_free(void* handle) {
    auto* d = static_cast<Decoder*>(handle);
    if (d == nullptr) return;
    ggwave_free(d->instance);
    delete d;
}

}  // extern "C"
