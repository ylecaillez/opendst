@param String simpleTitle
@param String subtitle
@param String startTime
@param String stateJson

<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>OpenDST Report - ${simpleTitle}</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script defer src="https://unpkg.com/alpinejs@3.14.9/dist/cdn.min.js"></script>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Fira+Code:wght@300;400;500;600;700&family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Inter', 'sans-serif'], mono: ['Fira Code', 'monospace'] },
                    colors: {
                        brand: { 50: '#ecfeff', 100: '#cffafe', 200: '#a5f3fc', 300: '#67e8f9', 400: '#22d3ee', 500: '#06b6d4', 600: '#0891b2', 700: '#0e7490', 800: '#155e75', 900: '#164e63', 950: '#083344' }
                    }
                }
            }
        }
    </script>
    <style>
        body { background-color: #020617; color: #f1f5f9; }
        .glow { box-shadow: 0 0 20px rgba(6, 182, 212, 0.1); }
        @@keyframes pulse-soft { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
        .animate-pulse-soft { animation: pulse-soft 3s cubic-bezier(0.4, 0, 0.6, 1) infinite; }
        [x-cloak] { display: none !important; }
    </style>
</head>
<body class="selection:bg-brand-500/30 font-sans"
      x-data="reportData"
      x-cloak
      id="report-body"
      data-state="${stateJson}">
    <nav class="sticky top-0 z-50 bg-slate-950/80 backdrop-blur-md border-b border-slate-800">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
            <div class="flex items-center justify-between h-16">
                <div class="flex items-center gap-4">
                    <span class="text-brand-400 font-mono font-bold text-xl tracking-tighter">&lt;OpenDST/&gt;</span>
                    <span class="text-slate-600 font-mono text-sm">/</span>
                    <span class="text-slate-300 font-mono text-sm">Execution Report</span>
                </div>
            </div>
        </div>
    </nav>

    <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
        <div class="mb-12 flex flex-col md:flex-row md:items-end justify-between gap-6">
            <div>
                <h1 class="text-4xl font-extrabold tracking-tight text-white mb-1">${simpleTitle}</h1>
                <p class="text-slate-500 font-mono text-xs mb-3">${subtitle}</p>
                <p class="text-slate-400 font-mono text-sm">Started at ${startTime}</p>
            </div>
            <div class="flex items-baseline gap-3">
                <span class="text-sm font-mono text-slate-500 uppercase tracking-widest">Global Status</span>
                <span id="global-status" class="text-5xl font-black tracking-tighter" :class="failed ? 'text-red-500' : 'text-emerald-500'" x-text="failed ? 'FAIL' : 'PASS'"></span>
            </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-12">
            <div class="p-6 bg-slate-900/50 border border-slate-800 rounded-2xl">
                <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1">Total Plans</div>
                <div id="total-plans" class="text-3xl font-mono font-bold text-white" x-text="totalPlans"></div>
            </div>
            <div class="p-6 bg-slate-900/50 border border-slate-800 rounded-2xl">
                <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1">Properties</div>
                <div id="signals-count" class="text-3xl font-mono font-bold text-brand-400" x-text="properties.length"></div>
            </div>
            <div class="p-6 bg-slate-900/50 border border-slate-800 rounded-2xl">
                <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1">Violations</div>
                <div id="violations-count" class="text-3xl font-mono font-bold text-red-400" x-text="violations"></div>
            </div>
            <div class="p-6 bg-slate-900/50 border border-slate-800 rounded-2xl">
                <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1">Uptime</div>
                <div class="text-3xl font-mono font-bold text-slate-300" x-text="uptime"></div>
            </div>
        </div>

        <!-- System Health Section -->
        <div class="mb-12 p-6 bg-slate-900/50 border border-slate-800 rounded-2xl flex items-center justify-between">
            <div>
                <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1">System Health</div>
                <div class="text-xl font-mono text-slate-300">Simulator Lifecycle</div>
            </div>
            <div class="text-right">
                <div class="text-3xl font-mono font-bold"
                     :class="systemHealth.status === 'pass' ? 'text-emerald-400' : systemHealth.status === 'fail' ? 'text-red-400' : 'text-slate-500'"
                     x-text="systemHealth.status.toUpperCase()"></div>
                <div class="text-xs font-mono text-slate-500 mt-1" x-text="systemHealth.firstFailLog ? 'Internal Error' : (systemHealth.status === 'pass' ? 'OK' : 'Pending')"></div>
            </div>
        </div>

        <div class="bg-slate-900/30 border border-slate-800 rounded-3xl overflow-hidden glow">
            <div class="px-8 py-6 border-b border-slate-800 bg-slate-900/50">
                <h3 class="text-xl font-bold text-white flex items-center gap-2">
                    <svg class="w-5 h-5 text-brand-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                    Verification Properties
                </h3>
            </div>
            <div class="px-8 pb-8">
                <table class="w-full">
                    <thead>
                        <tr class="text-left text-xs font-bold text-slate-500 uppercase tracking-widest border-b border-slate-800">
                            <th class="py-4">Property</th>
                            <th class="py-4">Kind</th>
                            <th class="py-4">Status</th>
                            <th class="py-4 text-right">Hit Count</th>
                            <th class="py-4 text-right">First Hit (it)</th>
                        </tr>
                    </thead>
                    <template x-for="s in properties" :key="s.label">
                        <tbody class="border-b border-slate-800">
                            <tr class="hover:bg-slate-900/50 transition-colors cursor-pointer group"
                                @click="expandedLabel = (expandedLabel === s.label ? null : s.label)">
                                <td class="py-4 font-mono text-brand-300">
                                    <div class="flex items-center gap-3">
                                        <svg class="w-4 h-4 text-slate-600 group-hover:text-brand-400 transition-transform duration-200" :class="expandedLabel === s.label ? 'rotate-90' : ''" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"></path>
                                        </svg>
                                        <span x-text="s.label"></span>
                                    </div>
                                </td>
                                <td class="py-4 font-mono" :class="{
                                    'text-purple-400': s.type === 'assert' && s.assertType === 'always',
                                    'text-blue-400': s.type === 'assert' && (s.assertType === 'sometimes' || s.assertType === 'alwaysOrUnreachable'),
                                    'text-slate-400': s.type === 'lifecycle',
                                    'text-orange-400': s.type === 'fault'
                                }" x-text="s.type === 'assert' ? s.assertType : s.type"></td>
                                <td class="py-4 font-mono" :class="s.status === 'pass' ? 'text-emerald-400' : s.status === 'fail' ? 'text-red-400' : s.status === 'flaky' ? 'text-orange-400' : 'text-slate-500'" x-text="s.status.toUpperCase()"></td>
                                <td class="py-4 text-right font-mono text-slate-300" x-text="s.hitCount"></td>
                                <td class="py-4 text-right font-mono text-slate-500" x-text="s.firstIteration === -1 ? '-' : s.firstIteration"></td>
                            </tr>
                            <tr class="bg-slate-950/50" x-show="expandedLabel === s.label" x-cloak>
                                <td colspan="5" class="px-8 py-6">
                                    <div class="mb-6">
                                        <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1">Source Location</div>
                                        <div class="text-sm font-mono text-brand-300" x-text="s.origin + ':' + s.line"></div>
                                    </div>
                                    <div class="flex flex-col gap-6">
                                        <div>
                                            <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-2">First Pass Log</div>
                                            <pre class="p-4 bg-slate-900 rounded-lg text-xs text-emerald-400 overflow-x-auto border border-emerald-500/20" x-text="s.firstPassLog || 'No passing log captured'"></pre>
                                        </div>
                                        <div>
                                            <div class="text-xs font-bold text-slate-500 uppercase tracking-widest mb-2">First Fail Log</div>
                                            <pre class="p-4 bg-slate-900 rounded-lg text-xs text-red-400 overflow-x-auto border border-red-500/20" x-text="s.firstFailLog || 'No failing log captured'"></pre>
                                        </div>
                                    </div>
                                </td>
                            </tr>
                        </tbody>
                    </template>
                </table>
                <div id="empty-state" x-show="properties.length === 0" class="py-12 text-center text-slate-500 font-mono text-sm">Waiting for properties...</div>
            </div>
        </div>
    </main>

    <script>
        document.addEventListener('alpine:init', () => {
            const body = document.getElementById('report-body');
            const initialState = JSON.parse(body.dataset.state);

            Alpine.data('reportData', () => ({
                expandedLabel: null,
                ...initialState
            }));
        });
    </script>
</body>
</html>
