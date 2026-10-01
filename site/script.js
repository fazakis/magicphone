// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
// Illustrations only: this site never connects to or controls a phone.
const examples = {
  coffee: {
    prompt: 'Open Maps and search for coffee nearby.',
    answer: 'On it. I’ll open Maps and look for nearby coffee spots.',
    state: 'A few taps, taken care of',
    steps: ['Open Maps', 'Search “coffee near me”', 'Show the results'],
    title: 'You’re in the right place.', result: 'Explore the results in Maps.',
  },
  settings: {
    prompt: 'Take me to my display settings.',
    answer: 'I’ll open Settings and find the display options for you.',
    state: 'A shortcut to what you need',
    steps: ['Open Settings', 'Find Display', 'Open display options'],
    title: 'Ready for your next move.', result: 'Adjust your display from here.',
  },
  question: {
    prompt: 'Search for a place to have lunch.',
    answer: 'What kind of food are you in the mood for?',
    state: 'Waiting for your reply',
    steps: ['Read your task', 'Ask for the missing detail', 'Continue after your answer'],
    title: 'A question, right where you are.', result: 'Tap the reply bubble to return to your chat.',
  },
};
document.querySelectorAll('[data-example]').forEach((button) => {
  button.addEventListener('click', () => {
    const example = examples[button.dataset.example];
    if (!example) return;
    document.querySelectorAll('[data-example]').forEach((item) => item.setAttribute('aria-pressed', String(item === button)));
    document.querySelector('#demo-prompt').textContent = example.prompt;
    document.querySelector('#demo-answer').textContent = example.answer;
    document.querySelector('#demo-state').textContent = example.state;
    const steps = example.steps.map((step, index) => {
      const item = document.createElement('li');
      const mark = document.createElement('span');
      mark.setAttribute('aria-hidden', 'true');
      mark.textContent = button.dataset.example === 'question' && index === 2 ? '·' : '✓';
      item.append(mark, document.createTextNode(step));
      return item;
    });
    document.querySelector('#demo-steps').replaceChildren(...steps);
    document.querySelector('#demo-result strong').textContent = example.title;
    document.querySelector('#demo-result > span').textContent = example.result;
  });
});
