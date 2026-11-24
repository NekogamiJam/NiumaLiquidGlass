---
language:
- en
license: cc-by-sa-4.0
library_name: numpy
pipeline_tag: text-generation
tags:
- next-word-prediction
- word-completion
- keyboard
- on-device
- lstm
- int8
datasets:
- allenai/soda
- OpenAssistant/oasst2
- google-research-datasets/taskmaster1
- google-research-datasets/taskmaster3
---

# Chime-SMS-2M

Chime is a small English model that predicts the next word and completes the word being typed. I built it for [LocalType](https://dr.eamer.dev/downloads/apps/localtype/), my Android keyboard, where it runs on the phone on every keystroke. It has 2,025,114 parameters, and the int8 model is a 2.2 MB file. [Try it in your browser](https://dr.eamer.dev/chime/).

**2.03M parameters · 2.22 MB int8 · 161.6M training words · 2h 2m on one A100 · 3.09–3.24 ms warm p95 on two Pixels**

Chime uses the CIFG recurrent architecture described for Gboard in 2018. Its 2.03M parameters sit alongside the published 1.4M model (2018) and 2.4–4.4M next-word models (2023). This compares published model sizes, not current installed models or prediction accuracy. [Measurements and shareable cards](https://dr.eamer.dev/chime/writeup.html) report the installed keyboard measurements separately from model specifications.

![Chime: 2.03M parameters, 2.22 MB int8, 161.6M training words, 2h 2m on one A100. Warm model inference p95: 3.09–3.24 ms.](comparison.png)

## Model details

- **Developed by:** Luke Steuber
- **Model type:** word-level language model: one CIFG-LSTM layer (670 units, projected to 96 dimensions) with tied input and output embeddings, the design of Gboard's published next-word model ([Hard et al., 2018](https://arxiv.org/abs/1811.03604))
- **Vocabulary:** 16,384 tokens: 16,054 words, 293 emoji, 31 punctuation marks and 6 special tokens
- **Context:** the sentence being typed, up to 63 tokens
- **Language:** English
- **License:** CC BY-SA 4.0 for the weights, Apache-2.0 for the code

## Uses

Chime ranks candidate words for a keyboard's suggestion strip: the next word after a space, or completions of a partly typed word. It is not a chat or text-generation model, and it does not correct spelling.

LocalType learns personal word choices separately; Chime’s weights stay fixed. From LocalType 0.5.13, a verified keep or accepted suggestion can qualify after one choice, while an ordinary known word needs two uses. The application uses up to three preceding words completed there and reserves at most one personal strip slot. Forget learned words clears that history. This application policy is separate from the model’s prediction measurements; the October 10 benchmark below measured it on LocalType alone.

## How to use

```sh
python3.12 -m venv .venv && . .venv/bin/activate
python -m pip install huggingface_hub
hf download lukeslp/chime-sms-2m --local-dir chime-sms-2m && cd chime-sms-2m
python -m pip install -r requirements.txt
python inference.py "see you "
```

A trailing space asks for the next word; without one, the last word is completed. From Python, `LocalTypeModel().predict("see you ", k=6)` in `inference.py` does the same. Inference needs only NumPy and runs offline.

`cifg_int8.bin` is the int8 model with its vocabulary, the file LocalType ships, and `read_bin` in `inference.py` reads both. `model.safetensors` holds the float32 weights; `config.json` lists their shapes.

## Related keyboard architecture

The keyboard architecture comparison comes from [Hard et al. (2018)](https://arxiv.org/abs/1811.03604), reporting 1.4M parameters and a 1.4 MB quantized file, and [Xu et al. (2023)](https://arxiv.org/abs/2305.18465), reporting 2.4–4.4M next-word models. These published generations do not identify current installed Gboard models.

## Keyboard comparison: editor-only benchmark, October 11, 2026

LocalType 0.5.14 (34), with Chime active, and Gboard 18.4.1.985164140-beta-arm64-v8a were compared on a Pixel 9a and a Pixel 10, both API 37, by typing 100 invented sentences into the same test editor with scripted touches at 60 ms. Each sentence carries two planted typos and two clean common words; each keyboard was reset once through its own supported controls before its block; nothing was read from either suggestion strip, and the score is the text that landed in the editor, with a paired bootstrap by sentence. Taps are scattered around each key's center by a fraction of the key pitch the two keyboards share, so both get the same pixels, and are held inside the keyboard's key area. Every cell completed on both phones. LocalType's text was identical on the two phones and Gboard's within two words, so the Pixel 9a is shown.

| Condition (200 planted typos) | LocalType corrected / wrong | Gboard corrected / wrong | Difference, 95% |
| --- | --- | --- | --- |
| 14 px tap spread, native | 134 / 15 | 103 / 10 | +0.155 [+0.10, +0.215] |
| 23.5 px tap spread, native | 127 / 15 | 106 / 21 | +0.105 [+0.045, +0.165] |
| 33 px tap spread, native | 110 / 26 | 100 / 38 | +0.05 [−0.01, +0.11] |
| 23.5 px tap spread, web | 125 / 15 | 106 / 21 | +0.095 [+0.035, +0.155] |

LocalType changed no clean word except at 33 px, where it changed 4 of 200 against Gboard's 5. Gboard corrects 100 to 108 of the typos at every spread; LocalType's count falls as taps leave their keys, and at 33 px the difference is not separated from zero. Thirteen of the 200 planted typos are themselves words; neither keyboard corrects them.

Earlier versions of this card quoted taps at exact key centers, 133 against 2 in a native field. That figure is withdrawn. Gboard changes behaviour when every tap lands on the exact center of its key, which no person's taps do. It corrects and completes normally for about the first dozen words of a block, then stops correcting in the native field and stops offering completions. With taps scattered by as little as 9 px it does not do this. So those rows did not measure Gboard. LocalType's own result there, 133 of 200, matches its 134 at 14 px.

This measures automatic correction of planted typos, which is LocalType's corrector and not Chime: Chime ranks the suggestion strip and never corrects spelling. On next-word prediction the two are at parity: a probe that read both suggestion strips on 150 human-written phrases, with scattered taps and nothing tapped, found the target offered before typing for 16 targets on LocalType and 15 on Gboard, and potential keystroke savings of 48.7% against 46.3% (+0.028 per word [0.000, +0.056]). Gboard echoes the typed prefix in one of its three slots, which tilts that comparison toward LocalType. The October 8 pilot that counted strip offers was confounded by unequal prior learning. Scripted taps are not human typing, and no speed, battery or everyday accuracy claim follows. [The benchmark page](https://dr.eamer.dev/chime/benchmark.html) has every cell, both phones, the limits and the corrections.

## Development prediction measurements

On 463 NUS SMS development lines, Chime scored 41.44% potential keystroke savings and 19.32% next-word top-three accuracy. On 567 Taskmaster-1 requests it scored 59.50% and 42.34%. The same Kotlin scorer started with no personal learning. Potential savings assumes the correct word is tapped as soon as it appears among three suggestions, so it is a simulated ceiling. These lines were not training text but were used to select the checkpoint; no held-out or everyday savings result is claimed. These measurements cannot be compared with correction counts or another keyboard’s prediction scores on different data.

A separate ordinary-field integration pilot verified live Chime predictions in all 60 eligible observations, with empty isolated learning before each case. It preserved the tested clean text and protected delivery controls. No next-word target was scored or prediction tapped. Different field and learning policies prevent combining that run with the keyboard benchmark into a model ranking.

## Runtime comparison

| Warm model inference, p95 | Pixel 9a | Pixel 10 |
| --- | ---: | ---: |
| Next word, retained context | 3.088 ms | 3.235 ms |
| Completion, retained context | 0.191 ms | 0.235 ms |
| Next word, fresh context | 6.675 ms | 7.578 ms |
| Long-context window reset subset | 14.134 ms | 17.638 ms |

Standalone Kotlin benchmarks on the exact published int8 checkpoint used 600 ordinary contexts over three passes and 30 stress sentences, pooling warm passes two and three. Model loading took 139.090/131.655 ms and first calls 18.941/22.952 ms on the 9a/10. Both ran API 37; thermal status stayed 0 on the 9a and rose from 0 to 1 on the 10. These are model-only timings, not a controlled phone comparison, typing latency or battery measurements.

Int8 export raised perplexity from 12.985256 to 12.998372 (+0.101%) on 5,000 development lines. Kotlin matched 120 exported contexts and top-ten rankings with maximum logit error 1.27e-5; NumPy and browser JavaScript matched the same references with maximum error below 5e-8. Numerical agreement is separate from prediction quality.

## Training

About 162 million words from five licensed English sources:

| Source | Text used | License |
| --- | --- | --- |
| [NUS SMS Corpus](https://doi.org/10.25540/WVM0-4RNX) | text messages people volunteered, mostly in Singapore, 2003 to 2015 | CC BY 4.0 |
| [Taskmaster-1](https://github.com/google-research-datasets/Taskmaster) | the user's side of written task dialogues | CC BY 4.0 |
| [Taskmaster-3](https://github.com/google-research-datasets/Taskmaster/tree/master/TM-3-2020) | the customer's side of written movie-ticket dialogues | CC BY 4.0 |
| [OpenAssistant 2](https://huggingface.co/datasets/OpenAssistant/oasst2) | English prompts written by people | Apache-2.0 |
| [SODA](https://huggingface.co/datasets/allenai/soda) | dialogue generated by GPT-3.5 | CC BY 4.0 |

SODA is synthetic and makes up most of the text, so the NUS SMS, Taskmaster-1 and OpenAssistant lines were repeated eight times in each pass. Phone numbers, URLs, email addresses and credential-like strings were filtered out, and the text was deduplicated before it was split. No private messages or typing logs were used.

I trained it from scratch for three passes (102,195 updates) on one A100 in about two hours, with AdamW at a learning rate of 0.002 (warmup, then cosine decay) and batches of 256 sentences of up to 64 tokens. I kept the checkpoint at step 98,000, which had the lowest development perplexity on the human-written sources, and quantized it to int8 with one scale per row, which raised development perplexity by 0.1%.

### Exact recipe and exposure

| Quantity | Measured value |
| --- | --- |
| Training split before repetition | 7,858,648 lines; 161,625,719 words (runs of letters) |
| Weighted pass | 8,720,859 lines; 195,641,031 model target tokens |
| Completed run | 102,195 updates; three batcher epochs; 586,885,109 target-token exposures |
| Selected checkpoint | Step 98,000; human-macro development perplexity 60.5425 |
| Final checkpoint | Step 102,195; human-macro development perplexity 60.9636 |
| Training loop | 7,342.98 seconds (2h 2m 23s) |
| Training computation | 7,021.45 seconds; 0.068706 seconds/update |
| Training-loop cost estimate | $5.10 at $2.50/hour; excludes setup/uploads; not a bill |
| Hardware / precision | One NVIDIA A100; PyTorch float32 |
| Batch / maximum sequence | 256 / 64 tokens |
| Optimizer | AdamW; weight decay 0.01; gradient norm clip 1.0 |
| Learning-rate schedule | 0.002 peak; 200-update warmup; cosine decay to 10% of peak |
| Seed | 20260925 |

×8 means repeating NUS SMS, Taskmaster-1 and OpenAssistant lines eight times per pass, while Taskmaster-3 and SODA appear once. It changes exposure, not parameter count or context size. Human-written lines form 12.868% of the weighted pass; SODA remains the majority. Repeated tokens are not additional unique data. The earlier ×4 checkpoint has the same architecture and is retained for comparison and rollback.

## Limitations

- English only. Suggestions reflect older Singapore texting, task dialogue, assistant prompts and synthetic dialogue, and the fixed vocabulary will not learn new words or names.
- The vocabulary was learned from the corpus and includes a few offensive words. LocalType removes them before showing suggestions; do the same in your own application.
- Keystrokes saved assumes the right word is tapped the moment it appears, so it is a ceiling. Savings in everyday typing have not been measured yet.
- Filtering removed text that looked sensitive, which does not prove the source text is anonymous.

## License

The weights and vocabulary are CC BY-SA 4.0, and `inference.py` and `localtype_tokenizer.py` are Apache-2.0; see [LICENSE.md](LICENSE.md). The training pipeline (corpus recipe, vocabulary, both trainers, the exporter) is at [github.com/lukeslp/chime-pipeline](https://github.com/lukeslp/chime-pipeline) under Apache-2.0; it holds no corpus text or weights. [NOTICE.txt](NOTICE.txt) credits the training data, whose authors do not endorse this model. The int8 model's SHA-256 is `3255dbeaf6e7fc0922afdca42ef751a22e3ad28903ab4acc9eb0022b9a704318`.

## Citation

```bibtex
@misc{steuber2026chimesms2m,
  author = {Luke Steuber},
  title = {Chime-SMS-2M: An On-Device English Keyboard Prediction Model},
  year = {2026},
  url = {https://huggingface.co/lukeslp/chime-sms-2m}
}
```

Also by me: [Drummer-540M](https://huggingface.co/lukeslp/drummer2-540m), a 542M-parameter language model trained from scratch.
