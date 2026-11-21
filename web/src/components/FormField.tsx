import type { InputHTMLAttributes } from "react";
import { inputClass } from "./ui";

interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  id: string;
  label: string;
  testId: string;
}

export function FormField({ id, label, testId, ...input }: FormFieldProps) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm text-zinc-300">
        {label}
      </label>
      <input id={id} data-testid={testId} className={inputClass} {...input} />
    </div>
  );
}
