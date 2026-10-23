"use client";

interface EmptyStateProps {
  starters: string[];
  onPick: (starter: string) => void;
}

export function EmptyState({ starters, onPick }: EmptyStateProps) {
  return (
    <section className="absolute inset-x-0 top-[calc(50%-210px)] mx-auto flex max-w-3xl flex-col items-center px-4 text-center min-[801px]:left-[260px] min-[801px]:right-0 min-[801px]:w-[calc(100%-260px)] min-[801px]:top-[calc(50%-320px)]">
      <h1 className="text-2xl leading-7 font-normal text-[rgb(13,13,13)]">
        What should I check for you?
      </h1>
      <p className="mt-2 max-w-2xl text-sm leading-5 text-[rgb(93,93,93)] max-[800px]:hidden">
        Paste a suspicious message, link or screenshot. I’ll show the risk evidence — never a guarantee of safety.
      </p>
      <div className="mt-[84px] flex w-full snap-x gap-2 overflow-x-auto pb-2 min-[801px]:mt-[68px] min-[801px]:grid min-[801px]:grid-cols-2 min-[801px]:overflow-visible min-[801px]:pb-0">
        {starters.map((starter, index) => (
          <button
            key={`${starter}-${index}`}
            type="button"
            onClick={() => onPick(starter)}
            className="flex min-h-[44px] min-w-[280px] snap-center items-center justify-center rounded-full border border-[rgba(0,0,0,0.1)] bg-white px-[14px] py-2 text-center text-sm leading-5 font-medium text-zinc-800 transition hover:bg-[rgb(243,243,243)] focus:outline-none focus:ring-2 focus:ring-[rgb(53,102,240)] focus:ring-offset-2 break-words min-[801px]:min-w-0 min-[801px]:py-0"
          >
            {starter}
          </button>
        ))}
      </div>
    </section>
  );
}

export default EmptyState;
