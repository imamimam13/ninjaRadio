import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: { filename: string } }
) {
  const filename = params.filename;
  try {
    const backendUrl = `http://backend:8000/api/callbook/${filename}`;
    const res = await fetch(backendUrl, { cache: "no-store" });
    if (!res.ok) {
      return new NextResponse(`Callbook file '${filename}' not found`, { status: 404 });
    }

    const contentType =
      res.headers.get("content-type") ||
      (filename.endsWith(".csv") ? "text/csv; charset=utf-8" : "application/json; charset=utf-8");

    const contentDisposition =
      res.headers.get("content-disposition") || `inline; filename="${filename}"`;

    const body = await res.text();
    return new NextResponse(body, {
      status: 200,
      headers: {
        "Content-Type": contentType,
        "Content-Disposition": contentDisposition,
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "public, max-age=60",
      },
    });
  } catch (err: any) {
    return new NextResponse(`Error proxying callbook: ${err.message}`, { status: 500 });
  }
}
