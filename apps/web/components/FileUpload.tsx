"use client";

import { useEffect, useRef, useState } from "react";

interface FileUploadProps {
  id: string;
  label: string;
  explanation: string;
  file: File | null;
  onFileSelect: (file: File | null) => void;
  onError: (error: string) => void;
  disabled?: boolean;
}

const MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
const ACCEPTED_TYPES = ["image/png", "image/jpeg", "image/webp"];
const ACCEPTED_EXTENSIONS = [".png", ".jpg", ".jpeg", ".webp"];

export function FileUpload({
  id,
  label,
  explanation,
  file,
  onFileSelect,
  onError,
  disabled = false,
}: FileUploadProps) {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [prevFile, setPrevFile] = useState<File | null>(file);
  const [isDragging, setIsDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // If parent cleared or changed file externally, adjust previewUrl during render
  if (file !== prevFile) {
    setPrevFile(file);
    if (!file && previewUrl) {
      setPreviewUrl(null);
    }
  }

  // Revoke object URL when previewUrl changes or component unmounts
  useEffect(() => {
    if (!previewUrl) return;
    return () => {
      URL.revokeObjectURL(previewUrl);
    };
  }, [previewUrl]);

  function validateAndSelect(selectedFile: File | null) {
    if (!selectedFile) {
      setPreviewUrl(null);
      onFileSelect(null);
      return;
    }

    const nameLower = selectedFile.name.toLowerCase();
    const hasValidExt = ACCEPTED_EXTENSIONS.some((ext) => nameLower.endsWith(ext));
    const hasValidType = ACCEPTED_TYPES.includes(selectedFile.type);

    if (!hasValidType && !hasValidExt) {
      onError("Unsupported file format. Please upload a PNG, JPEG, or WebP image.");
      if (inputRef.current) inputRef.current.value = "";
      return;
    }

    if (selectedFile.size > MAX_FILE_SIZE_BYTES) {
      const sizeMb = (selectedFile.size / (1024 * 1024)).toFixed(1);
      onError(`File exceeds the 5 MB limit (${sizeMb} MB). Please choose a smaller image.`);
      if (inputRef.current) inputRef.current.value = "";
      return;
    }

    onError("");
    const nextUrl = URL.createObjectURL(selectedFile);
    setPreviewUrl(nextUrl);
    onFileSelect(selectedFile);
  }

  function handleInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    const files = e.target.files;
    if (files && files.length > 0) {
      validateAndSelect(files[0]);
    }
  }

  function handleDrop(e: React.DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
    if (disabled) return;

    const files = e.dataTransfer.files;
    if (files && files.length > 0) {
      validateAndSelect(files[0]);
    }
  }

  function handleDragOver(e: React.DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    e.stopPropagation();
    if (!disabled) {
      setIsDragging(true);
    }
  }

  function handleDragLeave(e: React.DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLLabelElement>) {
    if (disabled) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      inputRef.current?.click();
    }
  }

  function handleClear(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (inputRef.current) inputRef.current.value = "";
    setPreviewUrl(null);
    onFileSelect(null);
    onError("");
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-col sm:flex-row sm:items-baseline sm:justify-between gap-1">
        <label htmlFor={id} className="text-sm font-medium text-slate-900 dark:text-slate-100">
          {label}
        </label>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          PNG, JPEG, WebP (max 5 MB)
        </span>
      </div>

      <p className="text-xs text-slate-600 dark:text-slate-400">{explanation}</p>

      <label
        htmlFor={id}
        tabIndex={disabled ? -1 : 0}
        onKeyDown={handleKeyDown}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        className={`flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-6 text-center cursor-pointer transition-colors focus:outline-none focus:ring-2 focus:ring-slate-500 focus:ring-offset-2 dark:focus:ring-offset-slate-900 ${
          disabled
            ? "cursor-not-allowed opacity-60 border-slate-300 dark:border-slate-700"
            : isDragging
            ? "border-blue-600 bg-blue-50/40 dark:border-blue-500 dark:bg-blue-950/20"
            : "border-slate-300 hover:border-slate-400 bg-slate-50/50 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900/40 dark:hover:bg-slate-900/60"
        }`}
      >
        <input
          id={id}
          ref={inputRef}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          onChange={handleInputChange}
          disabled={disabled}
          className="sr-only"
        />

        <div className="space-y-1">
          <p className="text-sm font-medium text-slate-800 dark:text-slate-200">
            {file ? "Replace image" : "Choose an image or drag & drop here"}
          </p>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Click to browse or press Enter when focused
          </p>
        </div>
      </label>

      {file && previewUrl && (
        <div className="mt-3 flex flex-col items-center gap-2 rounded-lg border border-slate-300 bg-slate-50/60 p-3 dark:border-slate-700 dark:bg-slate-900/50">
          <div className="relative max-h-60 max-w-full overflow-hidden rounded border border-slate-300 dark:border-slate-600">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={previewUrl}
              alt={`Preview of uploaded file ${file.name}`}
              className="max-h-56 max-w-full object-contain"
            />
          </div>
          <div className="flex w-full items-center justify-between text-xs text-slate-700 dark:text-slate-300">
            <span className="truncate font-mono">
              {file.name} ({(file.size / 1024).toFixed(0)} KB)
            </span>
            <button
              type="button"
              onClick={handleClear}
              disabled={disabled}
              className="ml-3 font-medium text-red-600 hover:text-red-800 dark:text-red-400 dark:hover:text-red-300 focus:outline-none focus:underline disabled:opacity-50"
            >
              Remove image
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
