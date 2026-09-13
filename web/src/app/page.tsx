const services = [
  ["Core", "Auth · Catalog · Playback"],
  ["Media", "Upload · FFmpeg · HLS"],
  ["Recommendations", "Events · Ranking · Statistics"],
] as const;

export default function Home() {
  return (
    <main className="mx-auto flex min-h-screen max-w-5xl flex-col justify-center px-6 py-20">
      <p className="mb-5 text-sm font-semibold uppercase tracking-[0.3em] text-amber-400">
        LIBRA Streaming
      </p>
      <h1 className="max-w-3xl text-5xl font-semibold tracking-tight sm:text-7xl">
        The VOD platform foundation is ready.
      </h1>
      <p className="mt-6 max-w-2xl text-lg leading-8 text-zinc-400">
        Three independently owned services, one protected media pipeline, and a
        frontend prepared for real contracts.
      </p>
      <section className="mt-14 grid gap-4 md:grid-cols-3" aria-label="Services">
        {services.map(([name, responsibility]) => (
          <article key={name} className="rounded-2xl border border-zinc-800 bg-zinc-900/60 p-6">
            <h2 className="text-xl font-medium">{name}</h2>
            <p className="mt-2 text-sm text-zinc-400">{responsibility}</p>
          </article>
        ))}
      </section>
    </main>
  );
}

