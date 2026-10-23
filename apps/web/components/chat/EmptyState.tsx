"use client";

interface EmptyStateProps {
  starters: string[];
  onPick: (starter: string) => void;
}

export function EmptyState({ starters, onPick }: EmptyStateProps) {
  return (
    <section className="mx-auto flex max-w-2xl flex-col items-center px-4 py-16 text-center">
      <h2 className="text-2xl leading-7 font-normal text-[rgb(13,13,13)]">
        What should I check for you?
      </h2>
      <p className="mt-3 max-w-xl text-base leading-6 text-[rgb(93,93,93)]">
        Paste a message or a link, attach a screenshot or QR code, or ask how a scam
        works. I show the evidence behind every answer and never say something is
        guaranteed safe.
      </p>
      <div className="mt-8 grid w-full grid-cols-1 gap-2 sm:grid-cols-2">
        {starters.map((starter, index) => (
          <button
            key={`${starter}-${index}`}
            type="button"
            onClick={() => onPick(starter)}
            className="flex min-h-[44px] items-center justify-center rounded-full border border-[rgba(0,0,0,0.1)] bg-white px-[14px] py-0 text-center text-sm leading-5 font-semibold text-[rgb(13,13,13)] transition hover:bg-[rgb(243,243,243)] focus:outline-none focus:ring-2 focus:ring-[rgb(53,102,240)] focus:ring-offset-2 break-words"
          >
            {starter}
          </button>
        ))}
      </div>
    </section>
  );
}

export default EmptyState;
