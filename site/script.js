// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
// Real app captures from the dedicated QA emulator; this gallery does not operate a phone.
const previews = {
  home: {
    src: 'assets/app-home.png',
    alt: 'Real MagicPhone home screen with task suggestions, voice input, selected model and Task, History, Library and Settings navigation.',
    label: 'Open the MagicPhone home screenshot at full size',
    caption: 'The real MagicPhone interface. Tap the screen to see it up close.',
  },
  models: {
    src: 'assets/app-models.png',
    alt: 'Real MagicPhone model settings showing GPT-6.1 Sol, manual model options, Ultra thinking and Ultrafast processing selected.',
    label: 'Open the MagicPhone model settings screenshot at full size',
    caption: 'Your model, thinking and speed controls. Availability depends on your connection.',
  },
  result: {
    src: 'assets/app-task-result.png',
    alt: 'A completed MagicPhone practice task showing requested GPT-6.1 Sol with Low thinking and Ultrafast speed; the server reported Standard processing.',
    label: 'Open the completed MagicPhone task screenshot at full size',
    caption: 'An actual practice task. Requested settings and server-reported results, side by side.',
  },
};
const preview = document.querySelector('#app-preview');
const fullSize = document.querySelector('#preview-full');
const caption = document.querySelector('#preview-caption');
document.querySelectorAll('[data-preview]').forEach((button) => {
  button.addEventListener('click', () => {
    const selected = previews[button.dataset.preview];
    if (!selected) return;
    document.querySelectorAll('[data-preview]').forEach((item) => item.setAttribute('aria-pressed', String(item === button)));
    preview.src = selected.src;
    preview.alt = selected.alt;
    fullSize.href = selected.src;
    fullSize.setAttribute('aria-label', selected.label);
    caption.textContent = selected.caption;
    if (window.innerWidth <= 800) preview.closest('.hero-visual').scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
      block: 'center',
    });
  });
});
