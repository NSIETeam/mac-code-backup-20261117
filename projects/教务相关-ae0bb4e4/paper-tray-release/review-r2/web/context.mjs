export function readClassroom(storage, userId, classes) {
 if(!userId)return null;
 try {const value=JSON.parse(storage.getItem('siyue-classroom:'+userId)||'null');return value && classes.some(c=>c.id===value.classId)?value:null;}catch{return null;}
}
export function saveClassroom(storage, userId, classes, next) {
 if(!userId||!classes.some(c=>c.id===next.classId))return null;
 const previous=readClassroom(storage,userId,classes);
 const value={...(previous?.classId===next.classId?previous:{}),...next};
 storage.setItem('siyue-classroom:'+userId,JSON.stringify(value));return value;
}
