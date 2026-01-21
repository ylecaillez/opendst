// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// https://astro.build/config
export default defineConfig({
	site: 'https://pingidentity.github.io',
	base: '/opendst',
	integrations: [
		starlight({
			title: 'OpenDST',
			description: 'Deterministic Simulation Testing for Java',

			social: [
				{
					icon: 'github',
					label: 'GitHub',
					href: 'https://github.com/pingidentity/opendst',
				},
			],
			customCss: ['./src/styles/custom.css'],
			head: [
				{
					tag: 'meta',
					attrs: {
						name: 'keywords',
						content: 'DST, deterministic simulation testing, Java, testing, distributed systems',
					},
				},
			],
			sidebar: [
				{ label: 'Why DST?', slug: 'why-dst' },
				{
					label: 'Documentation',
					items: [
						{ label: 'Getting Started', slug: 'documentation/getting-started' },
						{ label: 'Architecture', slug: 'documentation/architecture' },
						{ label: 'Test Session', slug: 'documentation/test-session' },
						{ label: 'Writing Tests', slug: 'documentation/writing-tests' },
						{ label: 'Assertions', slug: 'documentation/assertions' },
						{ label: 'Exploration & Branching', slug: 'documentation/exploration' },
						{ label: 'Instrumentation', slug: 'documentation/instrumentation' },
						{ label: 'Fault Injection', slug: 'documentation/faults' },
						{ label: 'Configuration', slug: 'documentation/configuration' },
						{ label: 'Reports & Observability', slug: 'documentation/observability' },
					],
				},
			],
		}),
	],
});
