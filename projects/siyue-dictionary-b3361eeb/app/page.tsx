import { requireChatGPTUser } from './chatgpt-auth';
import DictionaryApp from './dictionary-app';
export const dynamic = 'force-dynamic';
export default async function Home(){const user=await requireChatGPTUser('/');return <DictionaryApp user={{userId:user.userId,displayName:user.displayName}}/>;}
