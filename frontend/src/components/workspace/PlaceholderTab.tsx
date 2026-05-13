type Props = {
  title: string
  text: string
}

export function PlaceholderTab({ title, text }: Props) {
  return (
    <div>
      <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
        {title}
      </h2>
      <p className="text-sm text-slate-500 mt-1">{text}</p>
    </div>
  )
}
