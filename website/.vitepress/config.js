import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OpenDST',
  description: 'Deterministic Simulation Testing for Java',
  base: '/opendst/',

  head: [
    ['link', { rel: 'icon', href: '/opendst/favicon.svg' }],
    ['meta', { name: 'keywords', content: 'DST, deterministic simulation testing, Java, testing, distributed systems' }],
  ],

  themeConfig: {
    nav: [
      { text: 'Documentation', link: '/documentation/getting-started' },
      { text: 'Why DST?', link: '/why-dst' },
    ],

    sidebar: [
      {
        text: 'Introduction',
        items: [
          { text: 'Why DST?', link: '/why-dst' },
        ],
      },
      {
        text: 'Documentation',
        items: [
          { text: 'Getting Started', link: '/documentation/getting-started' },
          { text: 'Architecture', link: '/documentation/architecture' },
          { text: 'Test Session', link: '/documentation/test-session' },
          { text: 'Writing Tests', link: '/documentation/writing-tests' },
          { text: 'Assertions', link: '/documentation/assertions' },
          { text: 'Exploration & Branching', link: '/documentation/exploration' },
          { text: 'Instrumentation', link: '/documentation/instrumentation' },
          { text: 'Fault Injection', link: '/documentation/faults' },
          { text: 'Configuration', link: '/documentation/configuration' },
          { text: 'Reports & Observability', link: '/documentation/observability' },
        ],
      },
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/pingidentity/opendst' },
    ],

    search: {
      provider: 'local',
    },
  },
})
