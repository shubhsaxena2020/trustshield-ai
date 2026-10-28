"use client";

import { Canvas, useFrame } from "@react-three/fiber";
import { useMemo, useRef } from "react";
import { Shape, type Group, type Mesh } from "three";

function Shield() {
  const group = useRef<Group>(null);
  const shape = useMemo(() => {
    const path = new Shape();
    path.moveTo(-0.88, 0.98);
    path.lineTo(0.88, 0.98);
    path.lineTo(0.72, -0.18);
    path.lineTo(0, -1.04);
    path.lineTo(-0.72, -0.18);
    path.closePath();
    return path;
  }, []);
  useFrame(({ clock }, delta) => {
    if (group.current) {
      group.current.rotation.y = Math.sin(clock.elapsedTime * 0.55) * 0.16;
      group.current.rotation.x = Math.sin(clock.elapsedTime * 0.4) * 0.035;
      group.current.position.y = Math.sin(clock.elapsedTime * 0.7) * 0.08;
      group.current.rotation.z += delta * 0.025;
    }
  });

  return <group ref={group}>
    <mesh position={[0, 0, -0.18]}>
      <torusGeometry args={[1.28, 0.012, 8, 80]} />
      <meshBasicMaterial color="#bcbcb8" transparent opacity={0.72} />
    </mesh>
    <mesh>
      <extrudeGeometry args={[shape, { depth: 0.24, bevelEnabled: true, bevelSegments: 3, steps: 1, bevelSize: 0.055, bevelThickness: 0.07 }]} />
      <meshStandardMaterial color="#222222" roughness={0.26} metalness={0.18} />
    </mesh>
    <mesh position={[0, 0, 0.34]}>
      <torusGeometry args={[0.72, 0.018, 8, 64]} />
      <meshBasicMaterial color="#f4f4f2" />
    </mesh>
    <mesh position={[0, 0, 0.37]} rotation={[0, 0, -0.76]}>
      <boxGeometry args={[0.48, 0.13, 0.07]} />
      <meshStandardMaterial color="#f4f4f2" roughness={0.3} />
    </mesh>
    <mesh position={[0.23, 0.1, 0.37]} rotation={[0, 0, 0.72]}>
      <boxGeometry args={[0.9, 0.13, 0.07]} />
      <meshStandardMaterial color="#f4f4f2" roughness={0.3} />
    </mesh>
  </group>;
}

function Signal({ position, delay }: { position: [number, number, number]; delay: number }) {
  const mesh = useRef<Mesh>(null);
  useFrame(({ clock }) => {
    if (mesh.current) {
      mesh.current.position.x = position[0] + Math.sin(clock.elapsedTime * 1.4 + delay) * 0.1;
      mesh.current.position.y = position[1] + Math.cos(clock.elapsedTime * 1.2 + delay) * 0.13;
      const scale = 0.9 + Math.sin(clock.elapsedTime * 2 + delay) * 0.08;
      mesh.current.scale.setScalar(scale);
    }
  });
  return <mesh ref={mesh} position={position}>
    <sphereGeometry args={[0.21, 24, 24]} />
    <meshStandardMaterial color="#f2f2f2" roughness={0.34} emissive="#777777" emissiveIntensity={0.22} />
  </mesh>;
}

export default function ShieldScene() {
  return <Canvas className="three-canvas" camera={{ position: [0, 0, 5.2], fov: 42 }} dpr={[1, 1.35]} gl={{ alpha: true, antialias: false, powerPreference: "low-power" }}>
    <ambientLight intensity={1.2} />
    <directionalLight position={[3, 4, 5]} intensity={2.3} color="#ffffff" />
    <pointLight position={[-3, -2, 2]} intensity={10} color="#bdbdbd" distance={8} />
    <Shield />
    <Signal position={[-1.65, 0.88, 0.2]} delay={0} />
    <Signal position={[1.65, -0.7, 0.1]} delay={1.7} />
    <Signal position={[1.58, 0.95, -0.3]} delay={2.9} />
  </Canvas>;
}
