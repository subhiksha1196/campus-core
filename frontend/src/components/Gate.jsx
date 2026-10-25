import { useUser } from '../session.js';
import { Empty } from './ui.jsx';

export default function Gate({ roles, only, children }) {
  const user = useUser();
  if (!user) return null;
  if (!roles || roles.includes(user.role)) return children(user);
  return (
    <section className="card">
      <Empty icon="fa-lock" title="Not available for your role">
        {only || 'This page is not available for your role.'}
      </Empty>
    </section>
  );
}
