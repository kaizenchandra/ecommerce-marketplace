import './globals.css';

export const metadata = {
    title: 'Market Lane — Good finds, independent sellers',
    description: 'Discover, compare and shop from independent sellers.'
};
export default function Layout({children}: { children: React.ReactNode }) {
    return <html lang="en">
    <body>{children}</body>
    </html>
}
