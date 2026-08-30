const androidDownloadUrl =
  'https://github.com/nguyenvanduocit/RiddleBoox/releases/download/v0.4.0/RiddleBoox-v0.4.0.apk';

document.querySelectorAll('[data-android-download]').forEach((link) => {
  if (!androidDownloadUrl) return;
  link.href = androidDownloadUrl;
  link.classList.remove('is-pending');
  link.removeAttribute('aria-describedby');
});

const revealItems = document.querySelectorAll('.reveal');

if ('IntersectionObserver' in window) {
  const revealObserver = new IntersectionObserver(
    (entries, observer) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      });
    },
    { threshold: 0.14 },
  );

  revealItems.forEach((item) => revealObserver.observe(item));
} else {
  revealItems.forEach((item) => item.classList.add('is-visible'));
}

document.querySelectorAll('a[href^="#"]').forEach((link) => {
  link.addEventListener('click', (event) => {
    const target = document.querySelector(link.getAttribute('href'));
    if (!target) return;
    event.preventDefault();
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
});

const heroDemo = document.querySelector('.hero-boox-demo');

if (heroDemo) {
  const copy = heroDemo.querySelector('[data-demo-copy]');
  const status = heroDemo.querySelector('[data-demo-status]');
  const caption = heroDemo.querySelector('[data-demo-caption]');
  const turn = heroDemo.querySelector('[data-demo-turn]');
  const stages = [
    {
      state: 'write',
      turn: 1,
      status: 'listening',
      caption: 'write with your pen',
      lines: ['Today, something', 'feels heavy.'],
      duration: 3200,
    },
    {
      state: 'dissolve',
      turn: 1,
      status: 'dissolving',
      caption: 'making room for an answer',
      lines: ['Today, something', 'feels heavy.'],
      duration: 1100,
    },
    {
      state: 'think',
      turn: 1,
      status: 'thinking',
      caption: 'the page is listening',
      lines: [],
      duration: 1900,
    },
    {
      state: 'reply',
      turn: 1,
      status: 'writing back',
      caption: 'reply written in ink',
      lines: ["You don't have to carry it", 'alone tonight.'],
      duration: 3600,
    },
    {
      state: 'dissolve',
      turn: 1,
      status: 'settling',
      caption: 'letting the reply settle',
      lines: ["You don't have to carry it", 'alone tonight.'],
      duration: 1000,
    },
    {
      state: 'pause',
      turn: 1,
      status: 'turn complete',
      caption: 'a little later',
      lines: [],
      duration: 1200,
    },
    {
      state: 'write',
      turn: 2,
      status: 'listening',
      caption: 'write with your pen',
      lines: ['I still feel it,', 'but I can name it now.'],
      duration: 3200,
    },
    {
      state: 'dissolve',
      turn: 2,
      status: 'dissolving',
      caption: 'making room for an answer',
      lines: ['I still feel it,', 'but I can name it now.'],
      duration: 1100,
    },
    {
      state: 'think',
      turn: 2,
      status: 'thinking',
      caption: 'the page is listening',
      lines: [],
      duration: 1900,
    },
    {
      state: 'reply',
      turn: 2,
      status: 'writing back',
      caption: 'reply written in ink',
      lines: ['That is enough for tonight.', 'We can take it slowly.'],
      duration: 3600,
    },
    {
      state: 'dissolve',
      turn: 2,
      status: 'settling',
      caption: 'letting the reply settle',
      lines: ['That is enough for tonight.', 'We can take it slowly.'],
      duration: 1000,
    },
    {
      state: 'pause',
      turn: 2,
      status: 'turn complete',
      caption: 'a new thought begins',
      lines: [],
      duration: 1200,
    },
  ];

  let stageIndex = 0;
  let cycleNumber = 0;

  const renderCopy = (lines) => {
    copy.replaceChildren();
    let wordIndex = 0;

    lines.forEach((line, lineIndex) => {
      const lineElement = document.createElement('span');
      lineElement.className = 'demo-line';
      const words = line.split(/\s+/);
      const lineStart = lineIndex * 1.25;
      const wordStep = words.length > 1 ? 1.05 / (words.length - 1) : 0;

      words.forEach((word, wordInLine) => {
        const wordElement = document.createElement('span');
        wordElement.className = 'demo-word';
        wordElement.style.setProperty('--word-index', wordIndex);
        wordElement.style.setProperty('--word-delay', `${(lineStart + wordInLine * wordStep).toFixed(2)}s`);
        wordElement.textContent = word;
        lineElement.append(wordElement);
        wordIndex += 1;
      });

      copy.append(lineElement);
    });
  };

  const renderStage = (stage) => {
    status.textContent = stage.status;
    caption.textContent = stage.caption;
    renderCopy(stage.lines);
    heroDemo.dataset.state = stage.state;
    turn.textContent = String(stage.turn + cycleNumber * 2).padStart(2, '0');
  };

  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    stageIndex = stages.reduce((lastReply, stage, index) => stage.state === 'reply' ? index : lastReply, -1);
    renderStage(stages[stageIndex]);
  } else {
    const advance = () => {
      stageIndex = (stageIndex + 1) % stages.length;
      if (stageIndex === 0) cycleNumber += 1;
      renderStage(stages[stageIndex]);
      window.setTimeout(advance, stages[stageIndex].duration);
    };

    renderStage(stages[stageIndex]);
    window.setTimeout(advance, stages[stageIndex].duration);
  }
}
